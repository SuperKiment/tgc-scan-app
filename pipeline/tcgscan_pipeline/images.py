"""Card visual cache.

Downloads ``{image}/low.webp`` (``docs/pack-format.md`` §4.5) into
``pipeline/.cache/images/<lang>/<card_id>.webp`` and returns the local path. A file already in the
cache is never fetched again (FR-21), so a repeat run only pays for the new cards; the cache is
gitignored and restored by ``.github/workflows/packs.yml``.

The same politeness rules as the API client apply: a minimum delay between two requests and
exponential backoff. A 404 is **tolerated** — the card simply has no visual, which the pack
builder turns into ``has_embed = 0`` (FR-3). A body that is not a WebP file (the intermittent
plain-text ``no available server`` answer) is retried, never cached.

No image byte ever enters a pack: the cache only feeds the embedding stage.

Dry-run one set:

    python -m tcgscan_pipeline.images --lang fr --set base1 --max-cards 2
"""

from __future__ import annotations

import argparse
import logging
import os
import re
import sys
import threading
import time
from pathlib import Path
from types import TracebackType

import requests

from . import config
from .catalog import CardRow, crawl_set
from .tcgdex import TcgdexError, TcgdexUnavailable

log = logging.getLogger(__name__)

IMAGE_FILE_SUFFIX = ".webp"
"""Extension of a cached visual; matches ``config.IMAGE_SUFFIX``."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_UNSAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


def image_path(lang: str, card_id: str, root: Path | None = None) -> Path:
    """Return the cache path of one card's visual, without touching the filesystem."""
    return (root or config.IMAGE_CACHE_DIR) / _safe(lang, "lang") / (
        _safe(card_id, "card_id") + IMAGE_FILE_SUFFIX
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

    def path_for(self, lang: str, card_id: str) -> Path:
        """Cache path of one card's visual in this cache's root."""
        return image_path(lang, card_id, self.root)

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
            The cached file, or ``None`` when the card has no visual at all (missing stem or 404).

        Raises:
            TcgdexUnavailable: every attempt failed, or the asset answered a permanent error
                other than 404.
        """
        if not image_url:
            return None

        target = self.path_for(lang, card_id)
        if target.is_file() and target.stat().st_size > 0:
            return target

        url = f"{image_url.rstrip('/')}{config.IMAGE_SUFFIX}"
        last_reason = "no attempt was made"

        for attempt in range(1, self.max_retries + 1):
            self._throttle()
            try:
                response = self._session.get(url, timeout=self.timeout_s)
            except requests.RequestException as exc:
                last_reason = f"request failed: {exc}"
            else:
                status = response.status_code
                if status == 404:
                    log.info("%s: %s has no %s asset (404)", lang, card_id, config.IMAGE_SUFFIX)
                    return None
                if status in _RETRYABLE_STATUS:
                    last_reason = f"HTTP {status}"
                elif not response.ok:
                    raise TcgdexUnavailable(f"GET {url} -> HTTP {status}")
                elif not _is_webp(response.content):
                    # Not an image: the measured plain-text « no available server » answer.
                    last_reason = f"body is not a WebP file ({len(response.content)} B)"
                else:
                    self._store(target, response.content)
                    return target

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

    def _store(self, target: Path, payload: bytes) -> None:
        """Write ``payload`` to ``target`` through a temporary file, so a partial write is never
        mistaken for a cached visual."""
        target.parent.mkdir(parents=True, exist_ok=True)
        part = target.with_name(target.name + ".part")
        try:
            part.write_bytes(payload)
            os.replace(part, target)
        except OSError:
            part.unlink(missing_ok=True)
            raise


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
