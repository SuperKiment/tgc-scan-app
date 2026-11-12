"""Limitless TCG, an optional extra source of JA card visuals.

TCGdex lacks the image of some JA cards. For those, and only when the operator opted in
(``--jp-extra-images``), the visual is taken from `Limitless TCG <https://limitlesstcg.com>`_: one
**set page** request per set (``/cards/jp/<CODE>``, memoised) lists each card and the URL of its
image, then the image itself is downloaded into the shared image cache under the key
``ja-limitless`` (``<card_id>.png``), so it can never collide with a TCGdex file. The page's own
image URL is used verbatim: it is the ``_SM`` size (274×381 px), the closest to TCGdex's
``low.webp`` (245×337 px); the model squashes both to 224×224 anyway.

The source is **optional by construction**: nothing here ever raises. A missing set page (about 64
TCGdex JA sets have none), a missing card, a network error or an unparsable page only leave the
card without a visual, with a log line. Limitless politeness: at least
``config.LIMITLESS_MIN_REQUEST_INTERVAL_S`` between two requests, a descriptive generic
User-Agent, no redirect followed, bodies read under a byte budget, URLs restricted to the
Limitless hosts.

The card number of a Limitless URL is not zero-padded (``/cards/jp/SM1M/1``) while the TCGdex
``localId`` is (``001``): cards are matched on ``int(localId)``.

No image byte ever enters a pack: the cache only feeds the embedding stage.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from html.parser import HTMLParser
from pathlib import Path
from types import TracebackType
from urllib.parse import quote, urljoin, urlsplit

import cv2
import numpy as np
import requests

from . import config
from .catalog import CardRow
from .images import MAX_IMAGE_BYTES, ImageCache, is_cached
from .tcgdex import read_capped

log = logging.getLogger(__name__)

LIMITLESS_LANG = "ja"
"""The only pack language this source feeds."""

CACHE_KEY = "ja-limitless"
"""Directory key of the image cache for Limitless files, distinct from the TCGdex ``ja`` one."""

CACHE_SUFFIX = ".png"
"""Limitless serves PNG files; they are stored as such."""

_HOST = "limitlesstcg.com"
_CDN_HOST = "limitlesstcg.nyc3.cdn.digitaloceanspaces.com"

_MAX_PAGE_BYTES = 8 * 1024 * 1024
"""Byte budget of a set page. The largest measured page is ~300 kB."""

_MAX_CONSECUTIVE_FAILURES = 3
"""Hard failures (not 404) in a row after which the source switches itself off for the run."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


class _LimitlessFailure(Exception):
    """A request failed every attempt or answered something unusable; caught inside this module."""


class _NotFound(Exception):
    """The request answered 404."""


def _is_decodable(payload: bytes) -> bool:
    """True when OpenCV can decode ``payload`` — a truncated or corrupt PNG is never cached."""
    try:
        return cv2.imdecode(np.frombuffer(payload, np.uint8), cv2.IMREAD_COLOR) is not None
    except cv2.error:
        return False


def set_code(set_id: str) -> str:
    """Limitless set code of a TCGdex JA set id."""
    return config.LIMITLESS_SET_CODE_ALIASES.get(set_id, set_id)


def is_allowed_url(url: str) -> bool:
    """True for an ``https://`` URL on Limitless or its image CDN."""
    try:
        parts = urlsplit(url)
        host = parts.hostname
    except ValueError:
        return False
    if parts.scheme != "https" or not host:
        return False
    host = host.rstrip(".").casefold()
    return host == _CDN_HOST or host == _HOST or host.endswith(f".{_HOST}")


class _SetPageParser(HTMLParser):
    """Collect ``card number -> image URL`` from the ``card-search-grid`` of a set page."""

    def __init__(self, code: str, page_url: str) -> None:
        super().__init__(convert_charrefs=True)
        self._href = re.compile(rf"^/cards/jp/{re.escape(code)}/(\d+)$")
        self._page_url = page_url
        self._grid_depth = 0
        self._number: int | None = None
        self.images: dict[int, str] = {}

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag == "div":
            if self._grid_depth:
                self._grid_depth += 1
            elif "card-search-grid" in (attributes.get("class") or "").split():
                self._grid_depth = 1
        elif not self._grid_depth:
            return
        elif tag == "a":
            match = self._href.match(attributes.get("href") or "")
            self._number = int(match.group(1)) if match else None
        elif tag == "img" and self._number is not None:
            src = (attributes.get("src") or "").strip()
            if src and "card" in (attributes.get("class") or "").split():
                self.images.setdefault(self._number, urljoin(self._page_url, src))

    def handle_endtag(self, tag: str) -> None:
        if tag == "a":
            self._number = None
        elif tag == "div" and self._grid_depth:
            self._grid_depth -= 1


class LimitlessSource:
    """Rate-limited, never-raising source of JA visuals; see the module docstring."""

    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_interval_s: float = config.LIMITLESS_MIN_REQUEST_INTERVAL_S,
        max_retries: int = config.LIMITLESS_MAX_RETRIES,
        timeout_s: float = config.REQUEST_TIMEOUT_S,
        max_run_s: float = config.LIMITLESS_MAX_RUN_SECONDS,
    ) -> None:
        if max_retries < 1:
            raise ValueError(f"max_retries must be >= 1, got {max_retries}")
        self.min_interval_s = max(0.0, min_interval_s)
        self.max_retries = max_retries
        self.timeout_s = timeout_s
        self.max_run_s = max_run_s
        self._started_at = time.monotonic()
        self._owns_session = session is None
        self._session = session or requests.Session()
        self._session.headers.update({"User-Agent": config.LIMITLESS_USER_AGENT})
        self._throttle_lock = threading.Lock()
        self._last_request_at = 0.0
        self._pages: dict[str, dict[int, str]] = {}
        self._consecutive_failures = 0
        self._disabled = False

    # --- lifecycle ---------------------------------------------------------------------------

    def close(self) -> None:
        """Close the underlying session when this source created it."""
        if self._owns_session:
            self._session.close()

    def __enter__(self) -> "LimitlessSource":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- public ------------------------------------------------------------------------------

    def fetch(self, card: CardRow, images: ImageCache) -> Path | None:
        """Return the cached Limitless visual of ``card``, downloading it when absent.

        Returns ``None`` — never raises — when Limitless has no usable visual for the card.
        """
        target = images.path_for(CACHE_KEY, card.card_id, CACHE_SUFFIX)
        if is_cached(target):
            return target
        if self._disabled:
            return None
        if time.monotonic() - self._started_at > self.max_run_s:
            self._disabled = True
            log.warning(
                "Limitless used more than %.0f s of this run; the source is off for the rest of it",
                self.max_run_s,
            )
            return None

        local_id = card.local_id.strip()
        if not (local_id.isascii() and local_id.isdigit()):
            log.info("%s: localId %r is not numeric; no Limitless visual", card.card_id, local_id)
            return None
        number = int(local_id)

        url = self._set_images(card.set_id).get(number)
        if url is None:
            if self._pages.get(card.set_id):
                log.info("%s: not on the Limitless page of the set; no visual", card.card_id)
            return None

        try:
            payload = self._get(url, MAX_IMAGE_BYTES, is_page=False)
        except _NotFound:
            log.info("%s: Limitless image answered 404; no visual", card.card_id)
            return None
        except _LimitlessFailure as exc:
            self._record_failure()
            log.warning("%s: Limitless image unavailable (%s); no visual", card.card_id, exc)
            return None
        self._consecutive_failures = 0
        if not _is_decodable(payload):
            log.warning("%s: Limitless image is not a decodable picture; no visual", card.card_id)
            return None
        try:
            images.store(target, payload)
        except OSError as exc:
            log.warning("%s: Limitless image could not be cached (%s); no visual", card.card_id, exc)
            return None
        return target

    # --- internals ---------------------------------------------------------------------------

    def _set_images(self, set_id: str) -> dict[int, str]:
        """``card number -> image URL`` of one set, fetched once; empty on any failure."""
        if set_id in self._pages:
            return self._pages[set_id]

        code = set_code(set_id)
        page_url = config.LIMITLESS_PAGE_URL_TEMPLATE.format(code=quote(code, safe=""))
        found: dict[int, str] = {}
        try:
            body = self._get(page_url, _MAX_PAGE_BYTES, is_page=True)
        except _NotFound:
            log.info("%s: no Limitless page (%s); its cards stay without a visual", set_id, code)
        except _LimitlessFailure as exc:
            self._record_failure()
            log.warning("%s: Limitless set page unavailable (%s); skipped", set_id, exc)
        else:
            self._consecutive_failures = 0
            parser = _SetPageParser(code, page_url)
            try:
                parser.feed(body.decode("utf-8", errors="replace"))
                parser.close()
            except Exception as exc:  # noqa: BLE001 - optional source, must never fail a build
                log.warning("%s: Limitless set page could not be parsed (%s); skipped", set_id, exc)
            else:
                found = {n: u for n, u in parser.images.items() if is_allowed_url(u)}
                log.info("%s: Limitless page lists %d cards", set_id, len(found))
        self._pages[set_id] = found
        return found

    def _record_failure(self) -> None:
        self._consecutive_failures += 1
        if self._consecutive_failures >= _MAX_CONSECUTIVE_FAILURES and not self._disabled:
            self._disabled = True
            log.warning(
                "Limitless failed %d requests in a row; the source is off for the rest of the run",
                self._consecutive_failures,
            )

    def _throttle(self) -> None:
        """Block until at least ``min_interval_s`` has elapsed since the previous request."""
        if self.min_interval_s <= 0.0:
            return
        with self._throttle_lock:
            wait = self.min_interval_s - (time.monotonic() - self._last_request_at)
            if wait > 0.0:
                time.sleep(wait)
            self._last_request_at = time.monotonic()

    def _get(self, url: str, budget: int, *, is_page: bool) -> bytes:
        """GET ``url`` with retries and return its body.

        Raises:
            _NotFound: the server answered 404.
            _LimitlessFailure: refused URL, redirect, permanent error, over-budget or invalid body,
                or every attempt failed.
        """
        if not is_allowed_url(url):
            raise _LimitlessFailure(f"{url!r} is not an https Limitless URL")

        last_reason = "no attempt was made"
        for attempt in range(1, self.max_retries + 1):
            self._throttle()
            try:
                with self._session.get(
                    url, timeout=self.timeout_s, stream=True, allow_redirects=False
                ) as response:
                    status = response.status_code
                    if status == 404:
                        raise _NotFound(url)
                    if status in _RETRYABLE_STATUS:
                        last_reason = f"HTTP {status}"
                    elif not response.ok:
                        # 3xx included: a redirect would have to be re-validated, so it is refused.
                        raise _LimitlessFailure(f"GET {url} -> HTTP {status}")
                    else:
                        payload = read_capped(response, budget)
                        if len(payload) > budget:
                            raise _LimitlessFailure(f"GET {url} runs past the {budget} B budget")
                        declared = response.headers.get("Content-Length")
                        if (
                            declared is not None
                            and not response.headers.get("Content-Encoding")
                            and declared.isdigit()
                            and int(declared) != len(payload)
                        ):
                            last_reason = f"truncated body: {len(payload)} B of {declared} B"
                        elif not is_page and not payload.startswith(_PNG_SIGNATURE):
                            raise _LimitlessFailure(f"GET {url} did not return a PNG file")
                        elif is_page and not payload:
                            last_reason = "empty body"
                        else:
                            return payload
            except requests.RequestException as exc:
                last_reason = f"request failed: {exc}"

            if attempt < self.max_retries:
                delay = min(
                    config.RETRY_BACKOFF_BASE_S * (2 ** (attempt - 1)), config.RETRY_BACKOFF_MAX_S
                )
                log.warning(
                    "GET %s attempt %d/%d failed (%s); retrying in %.1f s",
                    url,
                    attempt,
                    self.max_retries,
                    last_reason,
                    delay,
                )
                time.sleep(delay)

        raise _LimitlessFailure(
            f"GET {url} failed after {self.max_retries} attempts; last error: {last_reason}"
        )
