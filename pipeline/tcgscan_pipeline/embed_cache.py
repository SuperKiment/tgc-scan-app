"""Cross-run embedding cache (FR-21).

A tiny SQLite key-value store at ``pipeline/.cache/embeddings.sqlite``, keyed exactly as
``docs/pack-format.md`` §4.8 prescribes — ``(card_id, lang, image_sha256, model_sha256)`` → the
int8 blob of ``embeddings.vec``. Including both hashes in the key means a cached vector is reused
only while the card's visual *and* the model are byte-identical: a new image or a new model simply
misses, so a correct vector can never be served for the wrong input.

The cache is disposable (gitignored, restored by ``.github/workflows/packs.yml``): deleting it only
costs the ONNX runs of the next build. A file written by an older layout is therefore rebuilt from
scratch rather than migrated.
"""

from __future__ import annotations

import logging
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from types import TracebackType

from . import config

log = logging.getLogger(__name__)

CACHE_SCHEMA = 1
"""Layout version of the cache file; a different value on disk triggers a rebuild."""

COMMIT_EVERY = 200
"""Rows buffered before an implicit commit, so a long run never loses much work."""

_DDL_META = (
    "CREATE TABLE IF NOT EXISTS cache_meta ("
    " key   TEXT PRIMARY KEY NOT NULL,"
    " value TEXT NOT NULL"
    ")"
)

_DDL_EMBEDDINGS = (
    "CREATE TABLE IF NOT EXISTS embeddings ("
    " card_id      TEXT NOT NULL,"
    " lang         TEXT NOT NULL,"
    " image_sha256 TEXT NOT NULL,"
    " model_sha256 TEXT NOT NULL,"
    " vec          BLOB NOT NULL,"
    " created_at   TEXT NOT NULL,"
    " PRIMARY KEY (card_id, lang, image_sha256, model_sha256)"
    ") WITHOUT ROWID"
)

_KEY_WHERE = (
    "card_id = ? AND lang = ? AND image_sha256 = ? AND model_sha256 = ?"
)


class EmbeddingCache:
    """Reusable int8 embeddings of one model, keyed by card, language and image bytes.

    The ``model_sha256`` of the loaded model is bound once, at construction, and takes part in
    every key: an instance can therefore only ever read and write vectors of that model.
    """

    def __init__(
        self,
        path: Path | None = None,
        *,
        model_sha256: str = config.EMBED_MODEL_SHA256,
        commit_every: int = COMMIT_EVERY,
    ) -> None:
        if not model_sha256:
            raise ValueError("model_sha256 must not be empty")
        self.path = Path(path) if path is not None else config.EMBED_CACHE_DB
        self.model_sha256 = model_sha256
        self.commit_every = max(1, commit_every)
        self.hits = 0
        self.misses = 0
        self.stores = 0
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(self.path)
        self._pending = 0
        self._ensure_schema()

    # --- lifecycle -------------------------------------------------------------------------

    def commit(self) -> None:
        """Flush the buffered rows to disk."""
        if self._pending:
            self._db.commit()
            self._pending = 0

    def close(self) -> None:
        """Commit what is pending and close the connection."""
        try:
            self.commit()
        finally:
            self._db.close()

    def __enter__(self) -> "EmbeddingCache":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    # --- cache -----------------------------------------------------------------------------

    def get(self, card_id: str, lang: str, image_sha256: str) -> bytes | None:
        """Return the cached int8 vector of that exact ``(card, lang, image, model)``, or ``None``.

        A row whose blob is not ``config.EMBED_DIM`` bytes long is treated as a miss and deleted:
        the cache is never allowed to feed a malformed vector to the pack builder.
        """
        key = (card_id, lang, image_sha256, self.model_sha256)
        row = self._db.execute(f"SELECT vec FROM embeddings WHERE {_KEY_WHERE}", key).fetchone()
        if row is None:
            self.misses += 1
            return None

        vec = bytes(row[0])
        if len(vec) != config.EMBED_DIM:
            log.warning(
                "embedding cache: %s/%s has %d bytes instead of %d; the row is dropped",
                lang,
                card_id,
                len(vec),
                config.EMBED_DIM,
            )
            self._db.execute(f"DELETE FROM embeddings WHERE {_KEY_WHERE}", key)
            self._pending += 1
            self.misses += 1
            return None

        self.hits += 1
        return vec

    def put(self, card_id: str, lang: str, image_sha256: str, vec: bytes) -> None:
        """Store the int8 vector of one card, replacing any row with the same key.

        Raises:
            ValueError: an empty key component, or a blob that is not ``config.EMBED_DIM`` bytes.
        """
        if not card_id:
            raise ValueError("card_id must not be empty")
        if not lang:
            raise ValueError("lang must not be empty")
        if not image_sha256:
            raise ValueError("image_sha256 must not be empty")
        if len(vec) != config.EMBED_DIM:
            raise ValueError(f"vec must be {config.EMBED_DIM} bytes, got {len(vec)}")

        self._db.execute(
            "INSERT OR REPLACE INTO embeddings"
            " (card_id, lang, image_sha256, model_sha256, vec, created_at)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (card_id, lang, image_sha256, self.model_sha256, sqlite3.Binary(vec), _utc_now()),
        )
        self.stores += 1
        self._pending += 1
        if self._pending >= self.commit_every:
            self.commit()

    def row_count(self) -> int:
        """Number of cached vectors, all models included."""
        return int(self._db.execute("SELECT count(*) FROM embeddings").fetchone()[0])

    def summary(self) -> str:
        """One-line reuse report for the build log (FR-21)."""
        return (
            f"embedding cache {self.path}: {self.hits} reused, {self.misses} missing, "
            f"{self.stores} stored"
        )

    # --- internals -------------------------------------------------------------------------

    def _ensure_schema(self) -> None:
        """Create the tables, rebuilding a file written by another layout version."""
        self._db.execute(_DDL_META)
        row = self._db.execute(
            "SELECT value FROM cache_meta WHERE key = 'cache_schema'"
        ).fetchone()
        found = _as_int(row[0]) if row is not None else None
        if found is not None and found != CACHE_SCHEMA:
            log.warning(
                "embedding cache %s uses layout %d, expected %d; it is rebuilt from scratch",
                self.path,
                found,
                CACHE_SCHEMA,
            )
            self._db.execute("DROP TABLE IF EXISTS embeddings")
            found = None
        self._db.execute(_DDL_EMBEDDINGS)
        if found is None:
            self._db.execute(
                "INSERT OR REPLACE INTO cache_meta (key, value) VALUES ('cache_schema', ?)",
                (str(CACHE_SCHEMA),),
            )
        self._db.commit()


def _as_int(value: object) -> int | None:
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return None


def _utc_now() -> str:
    """UTC ISO-8601 timestamp, same shape as ``pack_meta.built_at``."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
