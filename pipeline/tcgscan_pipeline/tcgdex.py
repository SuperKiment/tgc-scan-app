"""TCGdex API client.

Implements the crawl rules of ``docs/pack-format.md`` §4: a single ``requests.Session``, a minimum
delay between two requests, exponential backoff, and an explicit non-JSON branch — the API
intermittently answers a plain-text ``no available server`` body with a 200 status, which must be
treated as a retryable failure and never as data (FR-21).

Bodies are streamed under :data:`MAX_JSON_BYTES` and every API-provided id is percent-encoded
before it enters the request path: the catalogue is remote data, not trusted input.

Probe one language:

    python -m tcgscan_pipeline.tcgdex --probe fr
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
import threading
import time
from types import TracebackType
from typing import Any
from urllib.parse import quote

import requests

from . import config

log = logging.getLogger(__name__)

MAX_JSON_BYTES = 16 * 1024 * 1024
"""Byte budget of one JSON body. The largest measured response — a set with its full card list —
is a few hundred kB, so this leaves ample room while keeping a runaway body out of memory."""

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})

_READ_CHUNK_BYTES = 64 * 1024

_ERROR_BODY_BYTES = 8 * 1024
"""Enough of an error body to quote in a message; the rest is of no interest."""


class TcgdexError(RuntimeError):
    """Base class for every TCGdex failure raised by this module."""


class TcgdexNotFound(TcgdexError):
    """The API answered 404. The caller decides whether that is fatal (a set) or not (an image)."""


class TcgdexUnavailable(TcgdexError):
    """Every attempt failed. The release gate turns this into « publish nothing » (FR-22)."""


class TcgdexClient:
    """Rate-limited JSON client for one TCGdex deployment.

    The instance is safe to share between crawls of different languages: the rate limiter is
    global to the client, so the configured minimum interval holds across all its requests.
    """

    def __init__(
        self,
        base_url: str = config.API_BASE,
        *,
        session: requests.Session | None = None,
        min_interval_s: float = config.MIN_REQUEST_INTERVAL_S,
        max_retries: int = config.MAX_RETRIES,
        timeout_s: float = config.REQUEST_TIMEOUT_S,
    ) -> None:
        if max_retries < 1:
            raise ValueError(f"max_retries must be >= 1, got {max_retries}")
        self.base_url = base_url.rstrip("/")
        self.min_interval_s = max(0.0, min_interval_s)
        self.max_retries = max_retries
        self.timeout_s = timeout_s
        self._owns_session = session is None
        self._session = session or requests.Session()
        self._session.headers.update(
            {"Accept": "application/json", "User-Agent": config.USER_AGENT}
        )
        self._throttle_lock = threading.Lock()
        self._last_request_at = 0.0

    # --- lifecycle ---------------------------------------------------------------------------

    def close(self) -> None:
        """Close the underlying session when this client created it."""
        if self._owns_session:
            self._session.close()

    def __enter__(self) -> "TcgdexClient":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- requests ----------------------------------------------------------------------------

    def _throttle(self) -> None:
        """Block until at least ``min_interval_s`` has elapsed since the previous request."""
        if self.min_interval_s <= 0.0:
            return
        with self._throttle_lock:
            wait = self.min_interval_s - (time.monotonic() - self._last_request_at)
            if wait > 0.0:
                time.sleep(wait)
            self._last_request_at = time.monotonic()

    def _backoff(self, attempt: int) -> float:
        delay = config.RETRY_BACKOFF_BASE_S * (2 ** (attempt - 1))
        return min(delay, config.RETRY_BACKOFF_MAX_S)

    def get_json(self, path: str) -> Any:
        """GET ``path`` (relative to the base URL) and return the decoded JSON body.

        Raises:
            TcgdexNotFound: the resource does not exist (404).
            TcgdexUnavailable: transport error, retryable status or non-JSON body on every
                attempt, a body past :data:`MAX_JSON_BYTES`, or a non-retryable client error.
        """
        url = f"{self.base_url}/{path.lstrip('/')}"
        last_reason = "no attempt was made"

        for attempt in range(1, self.max_retries + 1):
            self._throttle()
            try:
                with self._session.get(url, timeout=self.timeout_s, stream=True) as response:
                    status = response.status_code
                    if status == 404:
                        raise TcgdexNotFound(f"GET {url} -> 404")
                    if status in _RETRYABLE_STATUS:
                        last_reason = f"HTTP {status}"
                    elif not response.ok:
                        # 4xx other than 404: retrying cannot help.
                        detail = _decode(read_capped(response, _ERROR_BODY_BYTES), response)
                        raise TcgdexUnavailable(f"GET {url} -> HTTP {status}: {_excerpt(detail)}")
                    else:
                        body = read_capped(response, MAX_JSON_BYTES)
                        if len(body) > MAX_JSON_BYTES:
                            # Not data: no catalogue response comes anywhere near that size.
                            raise TcgdexUnavailable(
                                f"GET {url} -> body runs past the {MAX_JSON_BYTES} B budget"
                            )
                        text = _decode(body, response)
                        try:
                            return json.loads(text)
                        except json.JSONDecodeError:
                            # The measured plain-text « no available server » answer.
                            last_reason = f"non-JSON body: {_excerpt(text)}"
            except requests.RequestException as exc:
                last_reason = f"request failed: {exc}"

            if attempt < self.max_retries:
                delay = self._backoff(attempt)
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

    # --- endpoints ---------------------------------------------------------------------------

    def series(self, lang: str) -> list[dict[str, Any]]:
        """``GET /{lang}/series`` — the brief of every series of that language."""
        path = f"{_segment(lang)}/series"
        return _as_list(self.get_json(path), path)

    def series_detail(self, lang: str, series_id: str) -> dict[str, Any]:
        """``GET /{lang}/series/{series_id}`` — the series with its set briefs."""
        path = f"{_segment(lang)}/series/{_segment(series_id)}"
        return _as_dict(self.get_json(path), path)

    def set_detail(self, lang: str, set_id: str) -> dict[str, Any]:
        """``GET /{lang}/sets/{set_id}`` — the set with its ``cardCount`` and full card list.

        This is the only call that returns card data: no per-card request is ever made.
        """
        path = f"{_segment(lang)}/sets/{_segment(set_id)}"
        return _as_dict(self.get_json(path), path)


def _segment(value: str) -> str:
    """Percent-encode one path segment.

    Series and set ids are API data pasted into the request path, so every reserved character —
    ``/`` included — is escaped: an id may never widen the URL into another resource.
    """
    return quote(value, safe="")


def read_capped(response: requests.Response, budget: int) -> bytes:
    """Read a streamed body, stopping one byte past ``budget``.

    The caller detects the overrun with ``len(body) > budget``; nothing beyond that single extra
    byte is ever buffered, so an unexpectedly huge answer costs neither memory nor bandwidth.
    Shared with :mod:`tcgscan_pipeline.images`, which streams the visuals under its own budget.
    """
    chunks: list[bytes] = []
    size = 0
    for chunk in response.iter_content(chunk_size=_READ_CHUNK_BYTES):
        if not chunk:  # keep-alive chunk
            continue
        chunks.append(chunk)
        size += len(chunk)
        if size > budget:
            break
    return b"".join(chunks)[: budget + 1]


def _decode(body: bytes, response: requests.Response) -> str:
    """Decode ``body`` with the response's charset, falling back to UTF-8 as the API declares."""
    return body.decode(response.encoding or "utf-8", errors="replace")


def _as_list(payload: Any, path: str) -> list[dict[str, Any]]:
    if not isinstance(payload, list):
        raise TcgdexUnavailable(f"GET {path} returned {type(payload).__name__}, expected a list")
    return payload


def _as_dict(payload: Any, path: str) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise TcgdexUnavailable(f"GET {path} returned {type(payload).__name__}, expected an object")
    return payload


def _excerpt(body: str, limit: int = 120) -> str:
    flat = " ".join(body.split())
    return flat if len(flat) <= limit else f"{flat[:limit]}…"


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline.tcgdex",
        description="Probe the TCGdex API with the pipeline's client.",
    )
    parser.add_argument(
        "--probe",
        metavar="LANG",
        required=True,
        help="language code to probe, e.g. fr or ja; prints its series count",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    with TcgdexClient() as client:
        try:
            series = client.series(args.probe)
        except TcgdexError as exc:
            log.error("%s", exc)
            return 1

    excluded = sorted(
        series_id
        for series_id in (str(entry.get("id")) for entry in series)
        if series_id.casefold() in config.EXCLUDED_SERIES
    )
    print(f"{args.probe}: {len(series)} series")
    print(f"{args.probe}: {len(series) - len(excluded)} kept, excluded {excluded or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
