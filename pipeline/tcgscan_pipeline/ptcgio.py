"""pokemontcg.io, an optional extra source of FR visuals.

TCGdex lacks the image of a few hundred FR cards, mostly FR prints of sets whose EN images exist on
the `pokemontcg.io <https://pokemontcg.io>`_ image CDN (Shiny Vaults, Trainer Galleries, Galarian
Gallery, McDonald's collections, promos…). For those, and only when the operator opted in
(``--fr-extra-images``), the visual is taken from there: the card is the same print with another
language's text, so it is an *other-language* visual, tagged ``has_embed = 2`` like the TCGdex
fallback and tried after it.

Only the sets of ``config.PTCGIO_SET_ALIASES`` are looked up: each is a TCGdex FR set whose numbering
was checked to be the pokemontcg.io one. For such a set **one JSON request** per set (the
``cards/en/<set>.json`` file of the open `pokemon-tcg-data
<https://github.com/PokemonTCG/pokemon-tcg-data>`_ repository, served by raw.githubusercontent.com,
memoised) lists every card with its exact ``images.small`` URL, so no card number format is guessed
(``H1`` vs ``H01``, ``question.png`` for the ``?`` Unown…). The pokemontcg.io API itself is never
used: it is not reliable enough for a build. Then the image is downloaded into the shared image cache
under the key ``en-ptcgio`` (``<card_id>.png``), so it can never collide with a TCGdex file.

Cards are matched on a normalised number: the TCGdex ``localId`` is percent-decoded (``%3F`` is
``?``), upper-cased and stripped of the zeros that pad its digits (``SV004`` = ``SV4``, ``H01`` =
``H1``); the pokemontcg.io card id (``<set>-<number>``) goes through the same rule. A set whose
numbering differs lists its cards in ``config.PTCGIO_NUMBER_OVERRIDES`` (``localId`` -> card id
suffix).

The source is **optional by construction**: nothing here ever raises. An unknown set, a card the
data does not list, a network error or an unparsable file only leave the card without a visual, with
a log line. Politeness: at least ``config.PTCGIO_MIN_REQUEST_INTERVAL_S`` between two requests, a
descriptive generic User-Agent, no redirect followed, bodies read under a byte budget, URLs
restricted to the two hosts above. After a few consecutive failed requests, or once the wall-clock
budget is spent, the source switches itself off for the rest of the run.

No image byte ever enters a pack: the cache only feeds the embedding stage.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
from pathlib import Path
from types import TracebackType
from urllib.parse import quote, unquote, urlsplit

import cv2
import numpy as np
import requests

from . import config
from .catalog import CardRow
from .images import MAX_IMAGE_BYTES, ImageCache, is_cached
from .tcgdex import read_capped

log = logging.getLogger(__name__)

PTCGIO_LANG = "fr"
"""The only pack language this source feeds."""

CACHE_KEY = "en-ptcgio"
"""Directory key of the image cache for pokemontcg.io files, distinct from every TCGdex one."""

CACHE_SUFFIX = ".png"
"""pokemontcg.io serves PNG files; they are stored as such."""

_DATA_HOST = "raw.githubusercontent.com"
_IMAGE_HOST = "images.pokemontcg.io"

_MAX_DATA_BYTES = 8 * 1024 * 1024
"""Byte budget of a per-set cards file. The largest measured one is ~450 kB."""

_MAX_CONSECUTIVE_FAILURES = 3
"""Hard failures (not 404) in a row after which the source switches itself off for the run."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

_NUMBER = re.compile(r"^([A-Za-z]*)0*(\d+)(.*)$", re.DOTALL)


class _PtcgioFailure(Exception):
    """A request failed every attempt or answered something unusable; caught inside this module."""


class _NotFound(Exception):
    """The request answered 404."""


def _is_decodable(payload: bytes) -> bool:
    """True when OpenCV can decode ``payload`` — a truncated or corrupt PNG is never cached."""
    try:
        return cv2.imdecode(np.frombuffer(payload, np.uint8), cv2.IMREAD_COLOR) is not None
    except cv2.error:
        return False


def number_key(number: str) -> str:
    """Comparison key of a card number: percent-decoded, upper-cased, digit padding removed.

    ``SV004`` and ``SV4`` give ``SV4``, ``H01`` and ``H1`` give ``H1``, ``15_A1`` gives ``15_A1``,
    ``%3F`` and ``?`` give ``?``; a number with no digit is only upper-cased.
    """
    decoded = unquote(number).strip()
    match = _NUMBER.match(decoded)
    if match is None:
        return decoded.upper()
    return f"{match.group(1).upper()}{match.group(2)}{match.group(3).upper()}"


def is_allowed_url(url: str, host: str) -> bool:
    """True for an ``https://`` URL on exactly ``host`` (this source's two hosts are fixed)."""
    try:
        parts = urlsplit(url)
        found = parts.hostname
    except ValueError:
        return False
    if parts.scheme != "https" or not found:
        return False
    return found.rstrip(".").casefold() == host


class PtcgioSource:
    """Rate-limited, never-raising source of FR visuals; see the module docstring."""

    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_interval_s: float = config.PTCGIO_MIN_REQUEST_INTERVAL_S,
        max_retries: int = config.PTCGIO_MAX_RETRIES,
        timeout_s: float = config.REQUEST_TIMEOUT_S,
        max_run_s: float = config.PTCGIO_MAX_RUN_SECONDS,
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
        self._session.headers.update({"User-Agent": config.PTCGIO_USER_AGENT})
        self._throttle_lock = threading.Lock()
        self._last_request_at = 0.0
        self._sets: dict[str, dict[str, str]] = {}
        self._consecutive_failures = 0
        self._disabled = False

    # --- lifecycle ---------------------------------------------------------------------------

    def close(self) -> None:
        """Close the underlying session when this source created it."""
        if self._owns_session:
            self._session.close()

    def __enter__(self) -> "PtcgioSource":
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
        """Return the cached pokemontcg.io visual of ``card``, downloading it when absent.

        Returns ``None`` — never raises — when pokemontcg.io has no usable visual for the card.
        """
        ptcg_set = config.PTCGIO_SET_ALIASES.get(card.set_id)
        if ptcg_set is None:
            return None
        target = images.path_for(CACHE_KEY, card.card_id, CACHE_SUFFIX)
        if is_cached(target):
            return target
        if self._disabled:
            return None
        if time.monotonic() - self._started_at > self.max_run_s:
            self._disabled = True
            log.warning(
                "pokemontcg.io used more than %.0f s of this run; the source is off for the rest "
                "of it",
                self.max_run_s,
            )
            return None

        local_id = card.local_id.strip()
        override = config.PTCGIO_NUMBER_OVERRIDES.get(card.set_id, {}).get(local_id)
        url = self._set_images(ptcg_set).get(number_key(override or local_id))
        if url is None:
            if self._sets.get(ptcg_set):
                log.info("%s: not in the pokemontcg.io data of %s; no visual", card.card_id, ptcg_set)
            return None

        try:
            payload = self._get(url, MAX_IMAGE_BYTES, host=_IMAGE_HOST, is_image=True)
        except _NotFound:
            log.info("%s: pokemontcg.io image answered 404; no visual", card.card_id)
            return None
        except _PtcgioFailure as exc:
            self._record_failure()
            log.warning("%s: pokemontcg.io image unavailable (%s); no visual", card.card_id, exc)
            return None
        self._consecutive_failures = 0
        if not _is_decodable(payload):
            log.warning("%s: pokemontcg.io image is not a decodable picture; no visual", card.card_id)
            return None
        try:
            images.store(target, payload)
        except OSError as exc:
            log.warning(
                "%s: pokemontcg.io image could not be cached (%s); no visual", card.card_id, exc
            )
            return None
        return target

    # --- internals ---------------------------------------------------------------------------

    def _set_images(self, ptcg_set: str) -> dict[str, str]:
        """``number key -> small image URL`` of one pokemontcg.io set, fetched once; empty on any
        failure."""
        if ptcg_set in self._sets:
            return self._sets[ptcg_set]

        data_url = config.PTCGIO_CARDS_URL_TEMPLATE.format(set_id=quote(ptcg_set, safe=""))
        found: dict[str, str] = {}
        try:
            body = self._get(data_url, _MAX_DATA_BYTES, host=_DATA_HOST, is_image=False)
        except _NotFound:
            log.info("%s: no pokemontcg.io data file; its cards stay without a visual", ptcg_set)
        except _PtcgioFailure as exc:
            self._record_failure()
            log.warning("%s: pokemontcg.io data unavailable (%s); skipped", ptcg_set, exc)
        else:
            self._consecutive_failures = 0
            try:
                found = _parse_set(ptcg_set, body)
            except (ValueError, TypeError, AttributeError) as exc:
                log.warning("%s: pokemontcg.io data could not be parsed (%s); skipped", ptcg_set, exc)
            else:
                log.info("%s: pokemontcg.io data lists %d images", ptcg_set, len(found))
        self._sets[ptcg_set] = found
        return found

    def _record_failure(self) -> None:
        self._consecutive_failures += 1
        if self._consecutive_failures >= _MAX_CONSECUTIVE_FAILURES and not self._disabled:
            self._disabled = True
            log.warning(
                "pokemontcg.io failed %d requests in a row; the source is off for the rest of the "
                "run",
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

    def _get(self, url: str, budget: int, *, host: str, is_image: bool) -> bytes:
        """GET ``url`` with retries and return its body.

        Raises:
            _NotFound: the server answered 404.
            _PtcgioFailure: refused URL, redirect, permanent error, over-budget or invalid body, or
                every attempt failed.
        """
        if not is_allowed_url(url, host):
            raise _PtcgioFailure(f"{url!r} is not an https {host} URL")

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
                        # A 3xx is not followed (allow_redirects=False): its body then fails the image or JSON check.
                        raise _PtcgioFailure(f"GET {url} -> HTTP {status}")
                    else:
                        payload = read_capped(response, budget)
                        if len(payload) > budget:
                            raise _PtcgioFailure(f"GET {url} runs past the {budget} B budget")
                        declared = response.headers.get("Content-Length")
                        if (
                            declared is not None
                            and not response.headers.get("Content-Encoding")
                            and declared.isdigit()
                            and int(declared) != len(payload)
                        ):
                            last_reason = f"truncated body: {len(payload)} B of {declared} B"
                        elif is_image and not payload.startswith(_PNG_SIGNATURE):
                            raise _PtcgioFailure(f"GET {url} did not return a PNG file")
                        elif not is_image and not payload:
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

        raise _PtcgioFailure(
            f"GET {url} failed after {self.max_retries} attempts; last error: {last_reason}"
        )


def _parse_set(ptcg_set: str, body: bytes) -> dict[str, str]:
    """``number key -> small image URL`` of a pokemon-tcg-data ``cards/en/<set>.json`` body.

    The key comes from the card id (``<set>-<number>``), not from ``number``: the ``cel25c`` numbers
    repeat (``15`` four times) while their ids do not. A card whose image is not an https
    ``images.pokemontcg.io`` URL (the newest sets point at another host) is left out.

    Raises:
        ValueError: the body is not JSON, or not a list of cards.
    """
    cards = json.loads(body.decode("utf-8"))
    if not isinstance(cards, list):
        raise ValueError("not a list of cards")
    prefix = f"{ptcg_set}-"
    found: dict[str, str] = {}
    for entry in cards:
        if not isinstance(entry, dict):
            continue
        card_id = str(entry.get("id") or "")
        small = (entry.get("images") or {}).get("small")
        if not card_id.startswith(prefix) or not isinstance(small, str):
            continue
        if is_allowed_url(small, _IMAGE_HOST):
            found.setdefault(number_key(card_id[len(prefix) :]), small)
    return found
