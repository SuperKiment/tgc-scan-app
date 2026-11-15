"""Pack builder.

Turns the crawl of one language into the artefact the app installs: a SQLite database with the
**verbatim DDL** of ``docs/pack-format.md`` §2 (authoritative), gzipped to ``pack-<lang>.db.gz``.

The builder is the last place where a pack can still be rejected, so it enforces in code what the
specification promises about the file it writes (FR-20/FR-22, AC-6 behaviour):

* no set of an excluded series ever reaches the pack — the crawl already filters them out, this
  module raises :class:`PackBuildError` if one appears anyway;
* ``embeddings`` holds exactly the cards whose ``has_embed`` is not ``0``, each vector exactly
  ``config.EMBED_DIM`` int8 bytes — no image byte, no other blob, can be in the file;
* a pack with no card is never produced.

No image is redistributed: the image *cache* only feeds the embedding stage and the pack stores
nothing but the TCGdex image stem (``cards.image_url``).

Dry-run the whole chain on a couple of sets:

    python -m tcgscan_pipeline.packbuild --lang fr --max-sets 2 --out /tmp/packtest
"""

from __future__ import annotations

import argparse
import contextlib
import gzip
import logging
import os
import shutil
import sqlite3
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

from . import config
from .catalog import TCGDEX_ASSET_PREFIX, CardRow, SetRow, crawl
from .embed import (
    HAS_EMBED_FALLBACK_IMAGE,
    HAS_EMBED_NONE,
    EmbedError,
    Embedder,
    FallbackImageResolver,
    embed_card,
    file_sha256,
)
from .embed_cache import EmbeddingCache
from .images import ImageCache
from .limitless import LIMITLESS_LANG, LimitlessSource
from .ptcgio import PTCGIO_LANG, PtcgioSource
from .tcgdex import TcgdexClient, TcgdexError

log = logging.getLogger(__name__)

DB_FILENAME_TEMPLATE = "pack-{lang}.db"
"""Name of the uncompressed database while it is being built, inside the output directory.

It carries the language, like the archive next to it: a CI run builds every language into the same
directory, and two builds must never meet on one file — nor leave a ``--keep-db`` copy whose
language cannot be told from its name. The app installs it as ``packs/<lang>/pack.db`` (§1).
"""

GZ_FILENAME_TEMPLATE = "pack-{lang}.db.gz"
"""Published asset name (``docs/pack-format.md`` §1 and §6 ``packs[].file``)."""

GZIP_LEVEL = 9
"""Packs are built once a week and downloaded on mobile data: compress as hard as gzip allows."""

_COPY_CHUNK_BYTES = 1 << 20


class PackBuildError(RuntimeError):
    """The pack cannot be built, or would violate ``docs/pack-format.md``; nothing is published."""


# --- schema (docs/pack-format.md §2, verbatim) ------------------------------------------------

DDL_TABLES: tuple[str, ...] = (
    """
    CREATE TABLE pack_meta (
        key   TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL
    )
    """,
    """
    CREATE TABLE sets (
        id                  TEXT PRIMARY KEY NOT NULL,
        name                TEXT NOT NULL,
        series_id           TEXT NOT NULL,
        card_count_total    INTEGER NOT NULL,
        card_count_official INTEGER NOT NULL,
        release_date        TEXT,
        symbol_url          TEXT,
        logo_url            TEXT
    )
    """,
    """
    CREATE TABLE cards (
        card_id   TEXT PRIMARY KEY NOT NULL,
        local_id  TEXT NOT NULL,
        name      TEXT NOT NULL,
        name_norm TEXT NOT NULL,
        set_id    TEXT NOT NULL REFERENCES sets(id),
        image_url TEXT,
        has_embed INTEGER NOT NULL DEFAULT 0
    )
    """,
    """
    CREATE TABLE embeddings (
        card_id TEXT PRIMARY KEY NOT NULL REFERENCES cards(card_id),
        vec     BLOB NOT NULL
    )
    """,
    """
    CREATE TABLE ocr_tokens (
        card_id TEXT NOT NULL REFERENCES cards(card_id),
        token   TEXT NOT NULL,
        PRIMARY KEY (card_id, token)
    )
    """,
)

DDL_INDICES: tuple[str, ...] = (
    "CREATE INDEX idx_cards_name_norm ON cards (name_norm)",
    "CREATE INDEX idx_cards_local_id  ON cards (local_id)",
    "CREATE INDEX idx_cards_set_id    ON cards (set_id)",
    "CREATE INDEX idx_ocr_tokens_token ON ocr_tokens (token)",
)

_INSERT_SET = (
    "INSERT INTO sets"
    " (id, name, series_id, card_count_total, card_count_official,"
    " release_date, symbol_url, logo_url)"
    " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
)

_INSERT_CARD = (
    "INSERT INTO cards (card_id, local_id, name, name_norm, set_id, image_url, has_embed)"
    " VALUES (?, ?, ?, ?, ?, ?, ?)"
)

_INSERT_EMBEDDING = "INSERT INTO embeddings (card_id, vec) VALUES (?, ?)"

_INSERT_TOKEN = "INSERT INTO ocr_tokens (card_id, token) VALUES (?, ?)"


# --- result ------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class PackBuild:
    """What one built pack contributes to ``manifest.json`` (``docs/pack-format.md`` §6)."""

    gz_path: Path
    gz_bytes: int
    """Size of the ``.gz`` asset — ``packs[].bytes``."""
    sha256: str
    """Hex sha256 of the ``.gz`` asset — ``packs[].sha256``."""
    card_count: int
    """Rows in ``cards`` — ``packs[].cardCount`` and ``pack_meta.card_count``."""
    installed_bytes: int
    """Size of the decompressed database — ``packs[].installedBytes`` (EC-3)."""


@dataclass(slots=True)
class _Stats:
    """Counters of one build, for the log line of a CI run."""

    sets: int = 0
    cards: int = 0
    own_image: int = 0
    limitless_image: int = 0
    ptcgio_image: int = 0
    fallback_image: int = 0
    no_image: int = 0
    tokens: int = 0
    reused: int = 0


# --- OCR tokens (docs/pack-format.md §2.3) ----------------------------------------------------


def ocr_tokens(card: CardRow, set_row: SetRow) -> tuple[str, ...]:
    """Return the ``ocr_tokens`` rows of one card: its search keys, lowercased and deduplicated.

    Verbatim §2.3: the bare ``local_id``, the printed fraction ``local_id/card_count_total``, the
    set id, and the promo/alphanumeric form of the ``local_id`` with its separators removed (so a
    printed « SV-043 » is found by the OCR reading « SV043 »). The set *name* is deliberately
    absent: §5 reads it from ``sets.name``.
    """
    tokens: list[str] = []

    def add(token: str) -> None:
        if token and token not in tokens:
            tokens.append(token)

    local_id = card.local_id.strip().casefold()
    compact = "".join(char for char in local_id if char.isalnum())
    add(local_id)
    add(compact)
    if set_row.card_count_total > 0:
        total = set_row.card_count_total
        if local_id:
            add(f"{local_id}/{total}")
        if compact:
            add(f"{compact}/{total}")
    add(set_row.set_id.strip().casefold())
    return tuple(tokens)


# --- build -------------------------------------------------------------------------------------


def build_pack(
    lang: str,
    out_dir: Path,
    *,
    max_sets: int | None = None,
    en_image_fallback: bool = False,
    jp_extra_images: bool = False,
    fr_extra_images: bool = False,
    keep_db: bool = False,
    client: TcgdexClient | None = None,
    embedder: Embedder | None = None,
    images: ImageCache | None = None,
    cache: EmbeddingCache | None = None,
) -> PackBuild:
    """Crawl ``lang``, embed its visuals and write ``out_dir/pack-<lang>.db.gz``.

    Args:
        lang: TCGdex language code of the pack, ``fr`` or ``ja``.
        out_dir: directory the artefact is written to; created when missing.
        max_sets: stop after that many sets — the cheap dry run of the whole chain.
        en_image_fallback: borrow the visual of a card that has none in its own language
            from the first of ``FALLBACK_IMAGE_LANGS`` that has one (D15/FR-4, off by default); such rows are tagged ``has_embed = 2``.
        jp_extra_images: for a ``ja`` card TCGdex has no image for, use the Limitless TCG one
            (off by default, optional: its failures never fail the build); such rows are tagged
            ``has_embed = 1``, the visual being the card's own JP print.
        fr_extra_images: for a ``fr`` card still without a visual after the other-language
            fallback, use the pokemontcg.io one (off by default, optional: its failures never fail
            the build); such rows are tagged ``has_embed = 2``, the visual being an EN image.
        keep_db: leave the uncompressed ``pack-<lang>.db`` next to the archive, for a local install
            with ``adb push``. It is deleted by default so the output directory holds only the
            publishable artefacts.
        client: TCGdex client to reuse; a private one is created and closed when omitted.
        embedder: loaded model to reuse across languages (the 24 MB graph costs more than the
            inferences of a small set); one is created when omitted.
        images: visual cache to reuse; one is created and closed when omitted.
        cache: cross-run embedding cache (FR-21); one on ``config.EMBED_CACHE_DB`` is created and
            closed when omitted.

    Returns:
        The :class:`PackBuild` of the archive: its path, size, sha256, card count and the
        decompressed size the device needs (``docs/pack-format.md`` §6).

    Raises:
        PackBuildError: an excluded series reached the builder, the pack came out empty, or the
            written file disagrees with ``docs/pack-format.md``.
        TcgdexUnavailable: a request failed every attempt; the release gate turns that into
            « publish nothing » (FR-22).
        EmbedError: the model asset is missing or mismatched, or a cached visual is unreadable —
            a wrong vector is never written silently.
    """
    if not lang:
        raise ValueError("lang must not be empty")
    if max_sets is not None and max_sets < 0:
        raise ValueError(f"max_sets must be >= 0, got {max_sets}")

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    db_path = out / DB_FILENAME_TEMPLATE.format(lang=lang)
    gz_path = out / GZ_FILENAME_TEMPLATE.format(lang=lang)
    # Neither a stale artefact of an earlier build nor a half-written one of this build may stay
    # behind to be mistaken for the output (FR-22: all of the artefacts, or none of them).
    db_path.unlink(missing_ok=True)
    gz_path.unlink(missing_ok=True)
    try:
        return _build_into(
            db_path,
            gz_path,
            lang,
            max_sets=max_sets,
            en_image_fallback=en_image_fallback,
            jp_extra_images=jp_extra_images,
            fr_extra_images=fr_extra_images,
            keep_db=keep_db,
            client=client,
            embedder=embedder,
            images=images,
            cache=cache,
        )
    except BaseException:
        db_path.unlink(missing_ok=True)
        gz_path.unlink(missing_ok=True)
        raise


def _build_into(
    db_path: Path,
    gz_path: Path,
    lang: str,
    *,
    max_sets: int | None,
    en_image_fallback: bool,
    jp_extra_images: bool,
    fr_extra_images: bool,
    keep_db: bool,
    client: TcgdexClient | None,
    embedder: Embedder | None,
    images: ImageCache | None,
    cache: EmbeddingCache | None,
) -> PackBuild:
    """Build the pack at ``db_path`` and ``gz_path``; :func:`build_pack` cleans up on failure."""
    with contextlib.ExitStack() as stack:
        api = client if client is not None else stack.enter_context(TcgdexClient())
        model = embedder if embedder is not None else Embedder()
        visuals = images if images is not None else stack.enter_context(ImageCache())
        vectors = (
            cache
            if cache is not None
            else stack.enter_context(EmbeddingCache(model_sha256=model.model_sha256))
        )
        resolver = (
            stack.enter_context(FallbackImageResolver(client=api)) if en_image_fallback else None
        )
        limitless = (
            stack.enter_context(LimitlessSource())
            if jp_extra_images and lang == LIMITLESS_LANG
            else None
        )
        ptcgio = (
            stack.enter_context(PtcgioSource())
            if fr_extra_images and lang == PTCGIO_LANG
            else None
        )

        with contextlib.closing(sqlite3.connect(db_path, isolation_level=None)) as conn:
            # Autocommit plus explicit transactions: one per set, so the long embedding loop never
            # holds a write lock while it waits on the network.
            conn.execute("PRAGMA foreign_keys = ON")
            for statement in DDL_TABLES:
                conn.execute(statement)

            stats = _fill(
                conn,
                lang,
                client=api,
                embedder=model,
                images=visuals,
                cache=vectors,
                resolver=resolver,
                limitless=limitless,
                ptcgio=ptcgio,
                max_sets=max_sets,
            )
            _write_meta(conn, lang, model.model_id, stats.cards)
            for statement in DDL_INDICES:
                conn.execute(statement)
            _check_pack(conn, lang)
            conn.execute("VACUUM")

        vectors.commit()
        log.info(
            "%s: %d sets, %d cards, %d vectors (%d own image, %d Limitless, %d other-language "
            "fallback, %d pokemontcg.io, %d reused), "
            "%d cards without a visual, %d OCR tokens",
            lang,
            stats.sets,
            stats.cards,
            stats.own_image + stats.limitless_image + stats.fallback_image + stats.ptcgio_image,
            stats.own_image,
            stats.limitless_image,
            stats.fallback_image,
            stats.ptcgio_image,
            stats.reused,
            stats.no_image,
            stats.tokens,
        )

    installed_bytes = db_path.stat().st_size
    _gzip_file(db_path, gz_path)
    if not keep_db:
        db_path.unlink(missing_ok=True)

    built = PackBuild(
        gz_path=gz_path,
        gz_bytes=gz_path.stat().st_size,
        sha256=file_sha256(gz_path),
        card_count=stats.cards,
        installed_bytes=installed_bytes,
    )
    log.info(
        "%s: %s written — %d B compressed, %d B installed, sha256 %s",
        lang,
        built.gz_path.name,
        built.gz_bytes,
        built.installed_bytes,
        built.sha256,
    )
    return built


def _fill(
    conn: sqlite3.Connection,
    lang: str,
    *,
    client: TcgdexClient,
    embedder: Embedder,
    images: ImageCache,
    cache: EmbeddingCache,
    resolver: FallbackImageResolver | None,
    limitless: LimitlessSource | None,
    ptcgio: PtcgioSource | None,
    max_sets: int | None,
) -> _Stats:
    """Crawl, embed and insert every set of ``lang``, one transaction per set."""
    stats = _Stats()
    for crawled in crawl(lang, client=client, max_sets=max_sets):
        set_row = crawled.set_row
        if _is_excluded_series(set_row.series_id):
            # The crawl filters these out: reaching this point means the filter broke, and a
            # digital-only set in a pack is exactly what FR-20 forbids.
            raise PackBuildError(
                f"set {set_row.set_id} of excluded series {set_row.series_id} reached the pack "
                f"builder (FR-20); {lang} is not built"
            )

        card_rows: list[tuple[str, str, str, str, str, str | None, int]] = []
        embed_rows: list[tuple[str, sqlite3.Binary]] = []
        token_rows: list[tuple[str, str]] = []
        for card in crawled.cards:
            result = embed_card(
                card,
                lang,
                embedder=embedder,
                images=images,
                cache=cache,
                fallback_resolver=resolver,
                limitless=limitless,
                ptcgio=ptcgio,
            )
            has_embed = HAS_EMBED_NONE if result is None else result.has_embed
            card_rows.append(
                (
                    card.card_id,
                    card.local_id,
                    card.name,
                    card.name_norm,
                    card.set_id,
                    card.image_url,
                    has_embed,
                )
            )
            if result is None:
                stats.no_image += 1
            else:
                embed_rows.append((result.card_id, sqlite3.Binary(result.vec)))
                stats.reused += int(result.reused)
                if result.from_ptcgio:
                    stats.ptcgio_image += 1
                elif result.has_embed == HAS_EMBED_FALLBACK_IMAGE:
                    stats.fallback_image += 1
                elif result.from_limitless:
                    stats.limitless_image += 1
                else:
                    stats.own_image += 1
            token_rows.extend((card.card_id, token) for token in ocr_tokens(card, set_row))

        _write_set(conn, set_row, card_rows, embed_rows, token_rows)
        stats.sets += 1
        stats.cards += len(card_rows)
        stats.tokens += len(token_rows)
        log.info(
            "%s: set %s stored — %d cards, %d vectors, %d OCR tokens",
            lang,
            set_row.set_id,
            len(card_rows),
            len(embed_rows),
            len(token_rows),
        )
    return stats


def _write_set(
    conn: sqlite3.Connection,
    set_row: SetRow,
    card_rows: list[tuple[str, str, str, str, str, str | None, int]],
    embed_rows: list[tuple[str, sqlite3.Binary]],
    token_rows: list[tuple[str, str]],
) -> None:
    """Insert one set and all of its rows in a single transaction."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        conn.execute(
            _INSERT_SET,
            (
                set_row.set_id,
                set_row.name,
                set_row.series_id,
                set_row.card_count_total,
                set_row.card_count_official,
                set_row.release_date,
                set_row.symbol_url,
                set_row.logo_url,
            ),
        )
        conn.executemany(_INSERT_CARD, card_rows)
        conn.executemany(_INSERT_EMBEDDING, embed_rows)
        conn.executemany(_INSERT_TOKEN, token_rows)
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")


def _write_meta(conn: sqlite3.Connection, lang: str, embed_model: str, card_count: int) -> None:
    """Fill the six required ``pack_meta`` keys of ``docs/pack-format.md`` §2.1."""
    conn.execute("BEGIN IMMEDIATE")
    try:
        conn.executemany(
            "INSERT INTO pack_meta (key, value) VALUES (?, ?)",
            (
                ("pack_schema", str(config.PACK_SCHEMA)),
                ("lang", lang),
                ("built_at", _utc_now()),
                ("card_count", str(card_count)),
                ("embed_model", embed_model),
                ("embed_dim", str(config.EMBED_DIM)),
            ),
        )
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")


def _check_pack(conn: sqlite3.Connection, lang: str) -> None:
    """Refuse a database that disagrees with ``docs/pack-format.md`` (AC-6 behaviour).

    Raises:
        PackBuildError: the pack is empty, carries an excluded series, has an ``embeddings`` row
            that no ``has_embed`` announces (or the other way round), stores a vector of the wrong
            length — which is also what forbids any image byte in the file — carries an
            ``image_url`` the app would refuse, or fails SQLite's own integrity check.
    """
    cards = _scalar(conn, "SELECT count(*) FROM cards")
    if cards == 0:
        raise PackBuildError(f"{lang}: the pack has no card; it is never published")

    offenders = sorted(
        series_id
        for (series_id,) in conn.execute("SELECT DISTINCT series_id FROM sets")
        if _is_excluded_series(series_id)
    )
    if offenders:
        raise PackBuildError(
            f"{lang}: the pack carries sets of excluded series {', '.join(offenders)} (FR-20)"
        )

    announced = _scalar(conn, "SELECT count(*) FROM cards WHERE has_embed != 0")
    stored = _scalar(conn, "SELECT count(*) FROM embeddings")
    if announced != stored:
        raise PackBuildError(
            f"{lang}: {announced} cards announce an embedding but {stored} are stored; "
            "docs/pack-format.md §2.2 requires one row per card with has_embed != 0"
        )
    orphans = _scalar(
        conn,
        "SELECT count(*) FROM embeddings e JOIN cards c ON c.card_id = e.card_id"
        " WHERE c.has_embed = 0",
    )
    if orphans:
        raise PackBuildError(f"{lang}: {orphans} embeddings belong to cards with has_embed = 0")

    malformed = _scalar(
        conn, "SELECT count(*) FROM embeddings WHERE length(vec) != ?", (config.EMBED_DIM,)
    )
    if malformed:
        raise PackBuildError(
            f"{lang}: {malformed} vectors are not {config.EMBED_DIM} int8 bytes; a pack contains "
            "embeddings only, never image bytes (FR-20)"
        )

    # The app follows a stem only when it starts with TCGDEX_ASSET_PREFIX, literally; the bytes are
    # compared here with substr() rather than LIKE, whose ASCII case folding would let a stem the
    # app refuses through (docs/pack-format.md §4.5).
    foreign = _scalar(
        conn,
        "SELECT count(*) FROM cards WHERE image_url IS NOT NULL AND substr(image_url, 1, ?) != ?",
        (len(TCGDEX_ASSET_PREFIX), TCGDEX_ASSET_PREFIX),
    )
    if foreign:
        raise PackBuildError(
            f"{lang}: {foreign} cards carry an image_url outside {TCGDEX_ASSET_PREFIX}; the app "
            "refuses such a stem, so it is stored as NULL (docs/pack-format.md §4.5)"
        )

    integrity = conn.execute("PRAGMA integrity_check").fetchone()
    if integrity is None or integrity[0] != "ok":
        raise PackBuildError(f"{lang}: SQLite integrity check failed: {integrity}")


def _is_excluded_series(series_id: str) -> bool:
    """True when ``series_id`` must never appear in a pack (FR-20).

    Mirrors ``catalog._is_excluded`` on purpose: this check must hold even if the crawl's own
    filter regresses. The comparison is case-insensitive because TCGdex answers mixed-case series
    ids in ``ja``.
    """
    return series_id.strip().casefold() in config.EXCLUDED_SERIES


def _gzip_file(source: Path, target: Path) -> None:
    """Compress ``source`` to ``target`` through a ``.part`` file.

    The gzip header carries neither a name nor a timestamp, so the archive adds no variation of
    its own: identical database bytes always compress to identical archives. A rebuild of the same
    catalogue still yields a new sha256, because ``pack_meta.built_at`` is rewritten every run; the
    fixed header is what makes two archives comparable byte for byte at all.
    """
    part = target.with_name(target.name + ".part")
    try:
        with open(part, "wb") as raw:
            with gzip.GzipFile(
                filename="", mode="wb", compresslevel=GZIP_LEVEL, fileobj=raw, mtime=0
            ) as archive:
                with open(source, "rb") as plain:
                    shutil.copyfileobj(plain, archive, _COPY_CHUNK_BYTES)
            raw.flush()
            os.fsync(raw.fileno())
        os.replace(part, target)
    except BaseException:
        part.unlink(missing_ok=True)
        raise


def _scalar(conn: sqlite3.Connection, sql: str, params: tuple[object, ...] = ()) -> int:
    """Run a counting query and return its single integer."""
    return int(conn.execute(sql, params).fetchone()[0])


def _utc_now() -> str:
    """UTC ISO-8601 timestamp, the shape of ``pack_meta.built_at`` (§2.1)."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# --- dry run -----------------------------------------------------------------------------------


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline.packbuild",
        description="Build the gzipped SQLite pack of one language.",
    )
    parser.add_argument("--lang", required=True, help="language code to build, e.g. fr or ja")
    parser.add_argument(
        "--out",
        type=Path,
        default=config.DEFAULT_OUT_DIR,
        metavar="DIR",
        help=f"output directory of pack-<lang>.db.gz (default {config.DEFAULT_OUT_DIR})",
    )
    parser.add_argument(
        "--max-sets",
        type=int,
        default=None,
        metavar="N",
        help="stop after N sets (dry run); the whole language is built when omitted",
    )
    parser.add_argument(
        "--en-image-fallback",
        action="store_true",
        help="borrow the visual of a card that has none in its language from the first of "
        "FALLBACK_IMAGE_LANGS that has one (D15/FR-4, off by default); the rows it produces are "
        "tagged has_embed=2",
    )
    parser.add_argument(
        "--jp-extra-images",
        action="store_true",
        help="for a ja card TCGdex has no image for, use the Limitless TCG one (off by default); "
        "the rows it produces are tagged has_embed=1",
    )
    parser.add_argument(
        "--fr-extra-images",
        action="store_true",
        help="for a fr card still without a visual, use the pokemontcg.io one (off by default); "
        "the rows it produces are tagged has_embed=2",
    )
    parser.add_argument(
        "--keep-db",
        action="store_true",
        help="keep the uncompressed pack-<lang>.db next to the archive, to adb-push it to a device",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    try:
        built = build_pack(
            args.lang,
            args.out,
            max_sets=args.max_sets,
            en_image_fallback=args.en_image_fallback,
            jp_extra_images=args.jp_extra_images,
            fr_extra_images=args.fr_extra_images,
            keep_db=args.keep_db,
        )
    # ValueError included: an empty --lang or a negative --max-sets is an operator mistake, and a
    # traceback is not how a command line reports one.
    except (PackBuildError, EmbedError, TcgdexError, ValueError, OSError, sqlite3.Error) as exc:
        log.error("%s", exc)
        return 1

    print(
        f"{built.gz_path}\tbytes={built.gz_bytes}\tsha256={built.sha256}\t"
        f"cards={built.card_count}\tinstalled_bytes={built.installed_bytes}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(_main())
