"""Poképédia, an optional extra source of FR own-print visuals.

`Poképédia <https://www.pokepedia.fr>`_ is the French community wiki of Pokémon. Its card articles
carry a scan of the **French** print (``Fichier:Carte_<Extension>_<number>.png``), so for a ``fr``
card it is the card's *own* visual: tagged ``has_embed = 1`` like a TCGdex image, and tried right
after it, before the other-language fallback and pokemontcg.io. It is only used when the operator
opted in (``--fr-pokepedia-images``).

How a card finds its file, without guessing a file name:

1. **One listing, memoised.** The MediaWiki API (``list=allimages``, prefix ``Carte_``, 500 files per
   request, ~60 requests) lists every card file with its URL. Each name is split into an
   *extension* (the wiki's set name) and a *number* token; the result is indexed as
   ``extension -> number key -> URL``. The listing is read once per run, on the first card that is
   not already cached.
2. **Set -> extension.** ``config.POKEPEDIA_EXTENSION_OVERRIDES`` first (set id -> extension name,
   for the sets whose name differs: Trainer Galleries, Coffre Étincelant, McDonald's « (France) »,
   trainer kits, promos…). Otherwise the TCGdex set name is matched case-, accent- and
   punctuation-insensitively against the extension names: exactly, else as the **unique** extension
   ending with it (TCGdex drops the series prefix: « Nobles Victoires » is « Noir & Blanc Nobles
   Victoires »). An ambiguous or absent match leaves the set without a visual, never a guess.
3. **Card -> file.** The TCGdex ``localId`` and the wiki number go through the same
   :func:`~tcgscan_pipeline.ptcgio.number_key` (digit padding removed, upper-cased: ``SV004`` =
   ``SV4``). When several files share a number, the plain one wins over a variant such as
   ``_(Pack_Récompense_Play!_Pokémon)``.

The image is downloaded as a MediaWiki **thumbnail** (``config.POKEPEDIA_THUMB_WIDTH`` px wide, the
model squashes to 224 anyway; the full file is ~750 kB) and falls back to the full-size file when
the thumbnail is unavailable. It is cached under the key ``fr-pokepedia`` (``<card_id>.png``), so it
can never collide with a TCGdex file.

The source is **optional by construction**: nothing here ever raises. An unmapped set, a card the
wiki does not list, a network error or an unparsable answer only leave the card without a visual,
with a log line. Politeness (a volunteer-run wiki, not a CDN): at least
``config.POKEPEDIA_MIN_REQUEST_INTERVAL_S`` between two requests, a descriptive generic User-Agent,
no redirect followed, bodies read under a byte budget, URLs restricted to the wiki host. After a few
consecutive failed requests, or once the wall-clock budget is spent, the source switches itself off
for the rest of the run. Files already cached are reused without any request (FR-21), so a run cut
by the budget resumes where it stopped.

No image byte ever enters a pack: the cache only feeds the embedding stage.
"""

from __future__ import annotations

import json
import logging
import re
import threading
import time
import unicodedata
from pathlib import Path
from types import TracebackType
from urllib.parse import urlsplit, urlunsplit

import cv2
import numpy as np
import requests

from . import config
from .catalog import CardRow, SetRow
from .images import MAX_IMAGE_BYTES, ImageCache, is_cached
from .ptcgio import number_key
from .tcgdex import read_capped

log = logging.getLogger(__name__)

POKEPEDIA_LANG = "fr"
"""The only pack language this source feeds."""

CACHE_KEY = "fr-pokepedia"
"""Directory key of the image cache for Poképédia files, distinct from every other source's."""

CACHE_SUFFIX = ".png"
"""Poképédia card files are PNG; they are stored as such."""

_HOST = "www.pokepedia.fr"
_IMAGE_PATH_PREFIX = "/images/"

_FILE_PREFIX = "Carte_"
"""Every card file of the wiki starts with it; the listing is one ``allimages`` prefix query."""

_LISTING_PAGE_SIZE = "500"
"""``ailimit`` of an unauthenticated MediaWiki client (the maximum)."""

_MAX_LISTING_PAGES = 200
"""Safety bound of the listing loop (~60 pages measured for ~29 000 files)."""

_MAX_LISTING_BYTES = 8 * 1024 * 1024
"""Byte budget of one listing page. The measured size is ~150 kB."""

_MAX_CONSECUTIVE_FAILURES = 3
"""Hard failures (not 404) in a row after which the source switches itself off for the run."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

_CARD_FILE = re.compile(
    r"^Carte_(?P<extension>.+?)_(?P<number>[^_()]*\d[^_()]*|[A-Za-z])(?P<variant>_\([^()]*\))?\.png$",
    re.IGNORECASE,
)
"""``Carte_<extension>_<number>[_(variant)].png``. The extension is the shortest prefix that leaves a
single number token (one with a digit, or a lone letter such as the Unown or Mew letters; no ``_`` or
parenthesis: any other word is part of an article name, not a card number) and an optional trailing
parenthetical variant: ``Carte_Collection_McDonald's_2019_(France)_4.png`` is extension
``Collection_McDonald's_2019_(France)``, number ``4``; ``Carte_Promo_SV_004_(Pack_Récompense).png``
is extension ``Promo_SV``, number ``004``, a variant."""

_FULL_IMAGE_PATH = re.compile(r"^/images/(?P<shard>[0-9a-f]/[0-9a-f]{2})/(?P<name>[^/]+)$")
"""Path of a full-size file; its thumbnail lives at ``/images/thumb/<shard>/<name>/<W>px-<name>``."""


class _PokepediaFailure(Exception):
    """A request failed every attempt or answered something unusable; caught inside this module."""


class _NotFound(Exception):
    """The request answered 404."""


def _is_decodable(payload: bytes) -> bool:
    """True when OpenCV can decode ``payload`` — a truncated or corrupt PNG is never cached."""
    try:
        return cv2.imdecode(np.frombuffer(payload, np.uint8), cv2.IMREAD_COLOR) is not None
    except cv2.error:
        return False


def _is_json_object(payload: bytes) -> bool:
    """True when ``payload`` is a JSON object — anything else is the wiki's transient error page."""
    try:
        return isinstance(json.loads(payload.decode("utf-8")), dict)
    except (ValueError, UnicodeDecodeError):
        return False


def normalize_extension(text: str) -> str:
    """Comparison key of a set or extension name.

    NFKD (``30ᵉ`` -> ``30e``), accents removed, case folded, every run of characters other than
    ``[a-z0-9]`` collapsed to one space: ``Dragons Éxaltés`` and ``Dragons_Exaltés`` agree, and
    ``Collection McDonald's 2019`` gives ``collection mcdonald s 2019``.
    """
    decomposed = unicodedata.normalize("NFKD", text).casefold()
    stripped = "".join(char for char in decomposed if unicodedata.category(char) != "Mn")
    return re.sub(r"[^a-z0-9]+", " ", stripped).strip()


def is_allowed_url(url: str, *, image: bool = False) -> bool:
    """True for an ``https://`` URL on the wiki host (and, for an ``image``, under ``/images/``)."""
    try:
        parts = urlsplit(url)
        host = parts.hostname
    except ValueError:
        return False
    if parts.scheme != "https" or not host or host.rstrip(".").casefold() != _HOST:
        return False
    return parts.path.startswith(_IMAGE_PATH_PREFIX) if image else True


def thumbnail_url(url: str, width: int) -> str | None:
    """MediaWiki thumbnail URL of a full-size file URL, or ``None`` when ``url`` has another shape."""
    parts = urlsplit(url)
    match = _FULL_IMAGE_PATH.match(parts.path)
    if match is None:
        return None
    shard, name = match.group("shard"), match.group("name")
    path = f"{_IMAGE_PATH_PREFIX}thumb/{shard}/{name}/{width}px-{name}"
    return urlunsplit((parts.scheme, parts.netloc, path, "", ""))


class PokepediaSource:
    """Rate-limited, never-raising source of FR own-print visuals; see the module docstring."""

    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_interval_s: float = config.POKEPEDIA_MIN_REQUEST_INTERVAL_S,
        max_retries: int = config.POKEPEDIA_MAX_RETRIES,
        timeout_s: float = config.REQUEST_TIMEOUT_S,
        max_run_s: float = config.POKEPEDIA_MAX_RUN_SECONDS,
        thumb_width: int = config.POKEPEDIA_THUMB_WIDTH,
    ) -> None:
        if max_retries < 1:
            raise ValueError(f"max_retries must be >= 1, got {max_retries}")
        if thumb_width < 1:
            raise ValueError(f"thumb_width must be >= 1, got {thumb_width}")
        self.min_interval_s = max(0.0, min_interval_s)
        self.max_retries = max_retries
        self.timeout_s = timeout_s
        self.max_run_s = max_run_s
        self.thumb_width = thumb_width
        self._started_at = time.monotonic()
        self._owns_session = session is None
        self._session = session or requests.Session()
        self._session.headers.update({"User-Agent": config.POKEPEDIA_USER_AGENT})
        self._throttle_lock = threading.Lock()
        self._last_request_at = 0.0
        self._index: dict[str, dict[str, str]] | None = None
        self._extensions: dict[str, dict[str, str] | None] = {}
        self._consecutive_failures = 0
        self._disabled = False

    # --- lifecycle ---------------------------------------------------------------------------

    def close(self) -> None:
        """Close the underlying session when this source created it."""
        if self._owns_session:
            self._session.close()

    def __enter__(self) -> "PokepediaSource":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- public ------------------------------------------------------------------------------

    def fetch(self, card: CardRow, set_row: SetRow | None, images: ImageCache) -> Path | None:
        """Return the cached Poképédia visual of ``card``, downloading it when absent.

        Returns ``None`` — never raises — when Poképédia has no usable visual for the card.
        """
        target = images.path_for(CACHE_KEY, card.card_id, CACHE_SUFFIX)
        if is_cached(target):
            return target
        if self._disabled or set_row is None:
            return None
        if time.monotonic() - self._started_at > self.max_run_s:
            self._disabled = True
            log.warning(
                "Poképédia used more than %.0f s of this run; the source is off for the rest of it",
                self.max_run_s,
            )
            return None

        files = self._extension_files(set_row)
        if files is None:
            return None
        url = files.get(number_key(card.local_id.strip()))
        if url is None:
            log.info("%s: not on Poképédia (%s); no visual", card.card_id, set_row.name)
            return None

        candidates = [url]
        thumb = thumbnail_url(url, self.thumb_width)
        if thumb is not None:
            candidates.insert(0, thumb)
        failure: _PokepediaFailure | None = None
        for candidate in candidates:
            try:
                payload = self._get(candidate, MAX_IMAGE_BYTES, image=True)
            except _NotFound:
                log.info("%s: Poképédia image %s answered 404", card.card_id, candidate)
                continue
            except _PokepediaFailure as exc:
                failure = exc
                continue
            self._consecutive_failures = 0
            failure = None
            if not _is_decodable(payload):
                log.warning("%s: Poképédia image %s does not decode", card.card_id, candidate)
                continue
            try:
                images.store(target, payload)
            except OSError as exc:
                log.warning(
                    "%s: Poképédia image could not be cached (%s); no visual", card.card_id, exc
                )
                return None
            return target

        if failure is not None:
            self._record_failure()
            log.warning("%s: Poképédia image unavailable (%s); no visual", card.card_id, failure)
        else:
            log.info("%s: no usable Poképédia image; no visual", card.card_id)
        return None

    # --- internals ---------------------------------------------------------------------------

    def _extension_files(self, set_row: SetRow) -> dict[str, str] | None:
        """``number key -> URL`` of the extension of ``set_row``, or ``None`` when it has none."""
        if set_row.set_id in self._extensions:
            return self._extensions[set_row.set_id]

        index = self._listing()
        files: dict[str, str] | None = None
        if index:
            files = self._resolve_extension(set_row, index)
        self._extensions[set_row.set_id] = files
        return files

    def _resolve_extension(
        self, set_row: SetRow, index: dict[str, dict[str, str]]
    ) -> dict[str, str] | None:
        override = config.POKEPEDIA_EXTENSION_OVERRIDES.get(set_row.set_id)
        key = normalize_extension(override or set_row.name)
        if key in index:
            log.info(
                "%s: Poképédia extension %s (%s, %d files)",
                set_row.set_id,
                key,
                "override" if override else "exact name",
                len(index[key]),
            )
            return index[key]
        if override:
            log.warning(
                "%s: Poképédia override %r matches no extension; the set stays without visuals",
                set_row.set_id,
                override,
            )
            return None
        if not key:
            return None

        suffix = f" {key}"
        matches = [extension for extension in index if extension.endswith(suffix)]
        if len(matches) == 1:
            log.info(
                "%s: Poképédia extension %s (name suffix, %d files)",
                set_row.set_id,
                matches[0],
                len(index[matches[0]]),
            )
            return index[matches[0]]
        if matches:
            log.warning(
                "%s: Poképédia name %r is ambiguous (%s); add an override. The set stays without "
                "visuals",
                set_row.set_id,
                set_row.name,
                ", ".join(sorted(matches)[:5]),
            )
        else:
            log.info("%s: no Poképédia extension named %r", set_row.set_id, set_row.name)
        return None

    def _listing(self) -> dict[str, dict[str, str]]:
        """``extension -> number key -> URL`` of every card file; read once, empty on any failure."""
        if self._index is not None:
            return self._index

        # (is variant, file name, url) of the best file of each (extension, number key).
        best: dict[str, dict[str, tuple[bool, str, str]]] = {}
        params = {
            "action": "query",
            "list": "allimages",
            "aiprefix": _FILE_PREFIX,
            "ailimit": _LISTING_PAGE_SIZE,
            "aiprop": "url",
            "format": "json",
        }
        files = 0
        try:
            for _ in range(_MAX_LISTING_PAGES):
                body = self._get(
                    config.POKEPEDIA_API_URL, _MAX_LISTING_BYTES, image=False, params=params
                )
                answer = json.loads(body.decode("utf-8"))
                if "error" in answer:
                    raise _PokepediaFailure(f"the API answered an error: {answer['error']}")
                for entry in (answer.get("query") or {}).get("allimages") or []:
                    files += int(_index_entry(entry, best))
                token = (answer.get("continue") or {}).get("aicontinue")
                if not token:
                    break
                if token == params.get("aicontinue"):
                    raise _PokepediaFailure("the listing does not advance")
                params["aicontinue"] = str(token)
            else:
                raise _PokepediaFailure(f"the listing runs past {_MAX_LISTING_PAGES} pages")
        except (_PokepediaFailure, _NotFound, ValueError, AttributeError, TypeError) as exc:
            self._record_failure()
            log.warning("Poképédia card listing unavailable (%s); the source finds no card", exc)
            self._index = {}
            return self._index

        self._consecutive_failures = 0
        self._index = {
            extension: {number: entry[2] for number, entry in numbers.items()}
            for extension, numbers in best.items()
        }
        log.info("Poképédia lists %d card files in %d extensions", files, len(self._index))
        return self._index

    def _record_failure(self) -> None:
        self._consecutive_failures += 1
        if self._consecutive_failures >= _MAX_CONSECUTIVE_FAILURES and not self._disabled:
            self._disabled = True
            log.warning(
                "Poképédia failed %d requests in a row; the source is off for the rest of the run",
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

    def _get(
        self, url: str, budget: int, *, image: bool, params: dict[str, str] | None = None
    ) -> bytes:
        """GET ``url`` with retries and return its body (a PNG when ``image``, else a JSON object).

        Raises:
            _NotFound: the server answered 404.
            _PokepediaFailure: refused URL, redirect, permanent error, over-budget or invalid body,
                or every attempt failed.
        """
        if not is_allowed_url(url, image=image):
            raise _PokepediaFailure(f"{url!r} is not an https {_HOST} URL")

        last_reason = "no attempt was made"
        for attempt in range(1, self.max_retries + 1):
            self._throttle()
            try:
                with self._session.get(
                    url, params=params, timeout=self.timeout_s, stream=True, allow_redirects=False
                ) as response:
                    status = response.status_code
                    if status == 404:
                        raise _NotFound(url)
                    if status in _RETRYABLE_STATUS:
                        last_reason = f"HTTP {status}"
                    elif not response.ok:
                        # A 3xx is not followed (allow_redirects=False): its body then fails the image or JSON check.
                        raise _PokepediaFailure(f"GET {url} -> HTTP {status}")
                    else:
                        payload = read_capped(response, budget)
                        if len(payload) > budget:
                            raise _PokepediaFailure(f"GET {url} runs past the {budget} B budget")
                        declared = response.headers.get("Content-Length")
                        if (
                            declared is not None
                            and not response.headers.get("Content-Encoding")
                            and declared.isdigit()
                            and int(declared) != len(payload)
                        ):
                            last_reason = f"truncated body: {len(payload)} B of {declared} B"
                        elif image and not payload.startswith(_PNG_SIGNATURE):
                            raise _PokepediaFailure(f"GET {url} did not return a PNG file")
                        elif not image and not _is_json_object(payload):
                            last_reason = "body is not a JSON object"
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

        raise _PokepediaFailure(
            f"GET {url} failed after {self.max_retries} attempts; last error: {last_reason}"
        )


def _index_entry(entry: object, best: dict[str, dict[str, tuple[bool, str, str]]]) -> bool:
    """Add one ``allimages`` entry to ``best``; True when it is a usable card file.

    A plain file wins over a variant, then the smaller file name, so the choice is deterministic.
    """
    if not isinstance(entry, dict):
        return False
    name = entry.get("name")
    url = entry.get("url")
    if not isinstance(name, str) or not isinstance(url, str) or not is_allowed_url(url, image=True):
        return False
    match = _CARD_FILE.match(name)
    if match is None:
        return False
    number = number_key(match.group("number"))
    candidate = (match.group("variant") is not None, name, url)
    numbers = best.setdefault(normalize_extension(match.group("extension")), {})
    current = numbers.get(number)
    if current is None or candidate[:2] < current[:2]:
        numbers[number] = candidate
    return True
