"""TCGdex API client.

Implements the crawl rules of ``docs/pack-format.md`` §4: a single ``requests.Session``, a minimum
delay between two requests, exponential backoff, and an explicit non-JSON branch — the API
intermittently answers a plain-text ``no available server`` body with a 200 status, which must be
treated as a retryable failure and never as data (FR-21).

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

import requests

from . import config

log = logging.getLogger(__name__)

_RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524})


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
                attempt, or a non-retryable client error.
        """
        url = f"{self.base_url}/{path.lstrip('/')}"
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
                    raise TcgdexNotFound(f"GET {url} -> 404")
                if status in _RETRYABLE_STATUS:
                    last_reason = f"HTTP {status}"
                elif not response.ok:
                    # 4xx other than 404: retrying cannot help.
                    raise TcgdexUnavailable(
                        f"GET {url} -> HTTP {status}: {_excerpt(response.text)}"
                    )
                else:
                    try:
                        return json.loads(response.text)
                    except json.JSONDecodeError:
                        # The measured plain-text « no available server » answer.
                        last_reason = f"non-JSON body: {_excerpt(response.text)}"

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
        return _as_list(self.get_json(f"{lang}/series"), f"{lang}/series")

    def series_detail(self, lang: str, series_id: str) -> dict[str, Any]:
        """``GET /{lang}/series/{series_id}`` — the series with its set briefs."""
        path = f"{lang}/series/{series_id}"
        return _as_dict(self.get_json(path), path)

    def set_detail(self, lang: str, set_id: str) -> dict[str, Any]:
        """``GET /{lang}/sets/{set_id}`` — the set with its ``cardCount`` and full card list.

        This is the only call that returns card data: no per-card request is ever made.
        """
        path = f"{lang}/sets/{set_id}"
        return _as_dict(self.get_json(path), path)


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
        str(entry.get("id")) for entry in series if entry.get("id") in config.EXCLUDED_SERIES
    )
    print(f"{args.probe}: {len(series)} series")
    print(f"{args.probe}: {len(series) - len(excluded)} kept, excluded {excluded or 'none'}")
    return 0


if __name__ == "__main__":
    sys.exit(_main())
