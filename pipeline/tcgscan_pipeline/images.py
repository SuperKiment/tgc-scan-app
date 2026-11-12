"""Card visual cache.

Downloads ``{image}/low.webp`` (``docs/pack-format.md`` §4.5) into
``pipeline/.cache/images/<lang>/<card_id>.webp`` and returns the local path. A file already in the
cache is never fetched again (FR-21), so a repeat run only pays for the new cards; the cache is
gitignored and restored by ``.github/workflows/packs.yml``.

The same politeness rules as the API client apply: a minimum delay between two requests and
exponential backoff. A 404 is **tolerated** — the card simply has no visual, which the pack
builder turns into ``has_embed = 0`` (FR-3). A body that is not a WebP file (the intermittent
plain-text ``no available server`` answer) is retried, never cached.

``image_url`` comes verbatim from the TCGdex JSON and becomes the request target, so it is checked
against an allow-list before any request: ``https`` only, host ``tcgdex.net`` or below. Redirects
are **not** followed (the asset host answers 200 directly, measured) and the body is streamed under
a byte budget, so a hostile or broken answer can neither move the request elsewhere nor fill the
runner's disk.

No image byte ever enters a pack: the cache only feeds the embedding stage.

Dry-run one set:

    python -m tcgscan_pipeline.images --lang fr --set base1 --max-cards 2
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import stat
import sys
import tempfile
import threading
import time
from pathlib import Path
from types import TracebackType
from urllib.parse import urlsplit

import requests

from . import config
from .catalog import CardRow, crawl_set
from .tcgdex import TcgdexError, TcgdexUnavailable, read_capped

log = logging.getLogger(__name__)

IMAGE_FILE_SUFFIX = ".webp"
"""Extension of a cached visual; matches ``config.IMAGE_SUFFIX``."""

ASSET_HOST = "tcgdex.net"
"""Only ``https://`` URLs on this host (or a subdomain of it) are ever requested."""

MAX_IMAGE_BYTES = 4 * 1024 * 1024
"""Byte budget of one visual. A ``low.webp`` measures ~16 kB, so this is ~250× the expected size;
a body running past it is refused instead of being buffered."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_UNSAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


def image_path(
    lang: str, card_id: str, root: Path | None = None, suffix: str = IMAGE_FILE_SUFFIX
) -> Path:
    """Return the cache path of one card's visual, without touching the filesystem.

    ``lang`` is only a cache directory key: a second image source uses a key of its own (and its
    own ``suffix``) so that its files never collide with the TCGdex ones.
    """
    return (root or config.IMAGE_CACHE_DIR) / _safe(lang, "lang") / (
        _safe(card_id, "card_id") + suffix
    )


def _safe(value: str, field: str) -> str:
    """Make ``value`` usable as a single path component (no traversal, no hidden file)."""
    cleaned = _UNSAFE_NAME.sub("_", value.strip()).lstrip(".")
    if not cleaned:
        raise ValueError(f"{field} {value!r} has no usable filename form")
    return cleaned


class ImageCache:
    """Rate-limited, resumable-by-file downloader for the card visuals of every language.

    The rate limiter is global to the instance, so sharing one cache across languages keeps the
    configured minimum interval across all of its requests.
    """

    def __init__(
        self,
        root: Path | None = None,
        *,
        session: requests.Session | None = None,
        min_interval_s: float = config.MIN_REQUEST_INTERVAL_S,
        max_retries: int = config.MAX_RETRIES,
        timeout_s: float = config.REQUEST_TIMEOUT_S,
    ) -> None:
        if max_retries < 1:
            raise ValueError(f"max_retries must be >= 1, got {max_retries}")
        self.root = root or config.IMAGE_CACHE_DIR
        self.min_interval_s = max(0.0, min_interval_s)
        self.max_retries = max_retries
        self.timeout_s = timeout_s
        self._owns_session = session is None
        self._session = session or requests.Session()
        self._session.headers.update({"Accept": "image/webp", "User-Agent": config.USER_AGENT})
        self._throttle_lock = threading.Lock()
        self._last_request_at = 0.0

    # --- lifecycle ---------------------------------------------------------------------------

    def close(self) -> None:
        """Close the underlying session when this cache created it."""
        if self._owns_session:
            self._session.close()

    def __enter__(self) -> "ImageCache":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- cache -------------------------------------------------------------------------------

    def path_for(self, lang: str, card_id: str, suffix: str = IMAGE_FILE_SUFFIX) -> Path:
        """Cache path of one card's visual in this cache's root."""
        return image_path(lang, card_id, self.root, suffix)

    def fetch_card(self, lang: str, card: CardRow) -> Path | None:
        """Cache the visual of a crawled :class:`~tcgscan_pipeline.catalog.CardRow`."""
        return self.fetch(lang, card.card_id, card.image_url)

    def fetch(self, lang: str, card_id: str, image_url: str | None) -> Path | None:
        """Return the local path of ``card_id``'s visual, downloading it when absent.

        Args:
            lang: TCGdex language code the card was crawled in.
            card_id: TCGdex card id, used as the cache file name.
            image_url: the image *stem* of ``cards.image_url``; ``None`` for a card without a
                visual.

        Returns:
            The cached file, or ``None`` when the card has no visual at all (missing stem, refused
            URL, over-budget body or 404).

        Raises:
            TcgdexUnavailable: every attempt failed, or the asset answered a permanent error
                other than 404.
        """
        if not image_url:
            return None

        target = self.path_for(lang, card_id)
        if is_cached(target):
            return target

        url = f"{image_url.rstrip('/')}{config.IMAGE_SUFFIX}"
        if not _is_allowed_asset_url(url):
            # The stem is API data: anything but an https tcgdex.net asset is never requested.
            log.warning(
                "%s: %s image url %r is not an https %s asset; card skipped",
                lang,
                card_id,
                url,
                ASSET_HOST,
            )
            return None
        last_reason = "no attempt was made"

        for attempt in range(1, self.max_retries + 1):
            self._throttle()
            try:
                with self._session.get(
                    url, timeout=self.timeout_s, stream=True, allow_redirects=False
                ) as response:
                    status = response.status_code
                    if status == 404:
                        log.info(
                            "%s: %s has no %s asset (404)", lang, card_id, config.IMAGE_SUFFIX
                        )
                        return None
                    if status in _RETRYABLE_STATUS:
                        last_reason = f"HTTP {status}"
                    elif 300 <= status < 400:
                        # Measured: the asset host answers 200 directly. A redirect would have to
                        # be re-validated hop by hop, so it fails the run instead of being trusted.
                        raise TcgdexUnavailable(
                            f"GET {url} -> HTTP {status} to {response.headers.get('Location')!r}: "
                            f"the asset host redirects; every hop must be validated against "
                            f"{ASSET_HOST} before redirects are followed again"
                        )
                    elif not response.ok:
                        raise TcgdexUnavailable(f"GET {url} -> HTTP {status}")
                    else:
                        declared = _declared_length(response)
                        if declared is not None and declared > MAX_IMAGE_BYTES:
                            log.warning(
                                "%s: %s declares %d B, over the %d B visual budget; card skipped",
                                lang,
                                card_id,
                                declared,
                                MAX_IMAGE_BYTES,
                            )
                            return None
                        payload = read_capped(response, MAX_IMAGE_BYTES)
                        if len(payload) > MAX_IMAGE_BYTES:
                            log.warning(
                                "%s: %s runs past the %d B visual budget; card skipped",
                                lang,
                                card_id,
                                MAX_IMAGE_BYTES,
                            )
                            return None
                        if declared is not None and len(payload) != declared:
                            last_reason = f"truncated body: {len(payload)} B of {declared} B"
                        elif not _is_webp(payload):
                            # Not an image: the measured « no available server » plain text.
                            last_reason = f"body is not a WebP file ({len(payload)} B)"
                        else:
                            self.store(target, payload)
                            return target
            except requests.RequestException as exc:
                last_reason = f"request failed: {exc}"

            if attempt < self.max_retries:
                delay = min(
                    config.RETRY_BACKOFF_BASE_S * (2 ** (attempt - 1)),
                    config.RETRY_BACKOFF_MAX_S,
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

        raise TcgdexUnavailable(
            f"GET {url} failed after {self.max_retries} attempts; last error: {last_reason}"
        )

    # --- internals ---------------------------------------------------------------------------

    def _throttle(self) -> None:
        """Block until at least ``min_interval_s`` has elapsed since the previous request."""
        if self.min_interval_s <= 0.0:
            return
        with self._throttle_lock:
            wait = self.min_interval_s - (time.monotonic() - self._last_request_at)
            if wait > 0.0:
                time.sleep(wait)
            self._last_request_at = time.monotonic()

    def store(self, target: Path, payload: bytes) -> None:
        """Write ``payload`` to ``target`` atomically, so a partial write is never mistaken for a
        cached visual.

        The temporary file is created by :func:`tempfile.mkstemp` *in the target directory* rather
        than at a guessable ``<name>.part``: the cache tree is restored from ``actions/cache``, so
        nothing under it is trusted to be what its name says. ``os.replace`` then swaps the real
        file in — and, since it never follows symlinks, a symlink sitting at ``target`` is
        replaced rather than written through.
        """
        target.parent.mkdir(parents=True, exist_ok=True)
        handle, temp_name = tempfile.mkstemp(
            dir=target.parent, prefix=f".{target.name}.", suffix=".part"
        )
        temp_path = Path(temp_name)
        try:
            with os.fdopen(handle, "wb") as temp_file:
                temp_file.write(payload)
            # mkstemp creates the file private; a cached visual is world-readable as before.
            os.chmod(temp_path, 0o644)
            os.replace(temp_path, target)
        except OSError:
            temp_path.unlink(missing_ok=True)
            raise


def is_cached(target: Path) -> bool:
    """True when ``target`` already holds a usable visual.

    Only a non-empty **regular** file counts: the cache tree comes back from ``actions/cache``, so
    a symlink there is treated as absent (and overwritten by :meth:`ImageCache.store`) instead of
    being read through to wherever it points.
    """
    try:
        info = target.lstat()
    except OSError:
        return False
    return stat.S_ISREG(info.st_mode) and info.st_size > 0


def _is_allowed_asset_url(url: str) -> bool:
    """True for an ``https://`` URL whose host is ``tcgdex.net`` or a subdomain of it."""
    try:
        parts = urlsplit(url)
        host = parts.hostname
    except ValueError:
        return False
    if parts.scheme != "https" or not host:
        return False
    host = host.rstrip(".").casefold()
    return host == ASSET_HOST or host.endswith(f".{ASSET_HOST}")


def _declared_length(response: requests.Response) -> int | None:
    """Return the ``Content-Length`` of ``response``, or ``None`` when it cannot be trusted.

    An absent, malformed or negative value, and any transfer that arrives content-encoded (the
    header then counts the encoded bytes, not the ones read), yield ``None``.
    """
    raw = response.headers.get("Content-Length")
    if raw is None or response.headers.get("Content-Encoding"):
        return None
    try:
        declared = int(raw)
    except ValueError:
        return None
    return declared if declared >= 0 else None


def _is_webp(payload: bytes) -> bool:
    """True for a RIFF/WEBP container — the only body the cache is allowed to keep."""
    return len(payload) > 12 and payload[:4] == b"RIFF" and payload[8:12] == b"WEBP"


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline.images",
        description="Cache the visuals of one set and print their local paths.",
    )
    parser.add_argument("--lang", required=True, help="language code, e.g. fr or ja")
    parser.add_argument("--set", required=True, metavar="SET_ID", help="set id, e.g. base1")
    parser.add_argument(
        "--max-cards",
        type=int,
        default=None,
        metavar="N",
        help="stop after N cards of the set; the whole set is fetched when omitted",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    try:
        crawled = crawl_set(args.lang, args.set)
        cards = crawled.cards if args.max_cards is None else crawled.cards[: args.max_cards]
        # The asset host is not the API host, hence a session (and a limiter) of its own.
        with ImageCache() as cache:
            for card in cards:
                path = cache.fetch_card(args.lang, card)
                print(f"{card.card_id}\t{path or 'no-image'}")
    except TcgdexError as exc:
        log.error("%s", exc)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(_main())
