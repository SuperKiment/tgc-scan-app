"""Catalogue crawl.

Implements the crawl rules of ``docs/pack-format.md`` §4 (authoritative): series list → series
detail for the set ids → set detail for the sets *and their full card lists*, with the ``tcgp``
series excluded (FR-20) and **no per-card request**.

``name_norm`` is produced here by :func:`normalize_name`, the canonical normalisation of
``docs/pack-format.md`` §5.1; ``scan/text/TextNormalizer.kt`` implements the very same rule on the
device and the two must stay identical.

Dry-run one language:

    python -m tcgscan_pipeline.catalog --lang fr --max-sets 2
"""

from __future__ import annotations

import argparse
import logging
import sys
import unicodedata
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Any

from . import config
from .tcgdex import TcgdexClient, TcgdexError, TcgdexNotFound

log = logging.getLogger(__name__)

# --- canonical normalisation (docs/pack-format.md §5.1) ---------------------------------------

_KANA_CJK_RANGES: tuple[tuple[int, int], ...] = (
    (0x3005, 0x3006),  # 々 〆, used inside Japanese names
    (0x3041, 0x3096),  # hiragana letters
    (0x309D, 0x309F),  # ゝ ゞ ゟ
    (0x30A1, 0x30FA),  # katakana letters
    (0x30FC, 0x30FF),  # ー ヽ ヾ ヿ (the prolonged sound mark matters, ・ at 30FB does not)
    (0x31F0, 0x31FF),  # katakana phonetic extensions
    (0x3400, 0x4DBF),  # CJK unified ideographs extension A
    (0x4E00, 0x9FFF),  # CJK unified ideographs
    (0xF900, 0xFAFF),  # CJK compatibility ideographs
    (0xFF66, 0xFF9D),  # halfwidth katakana
    (0x20000, 0x2FA1F),  # CJK unified ideographs extensions B..F and compatibility supplement
)


def _is_kana_or_cjk(char: str) -> bool:
    """True for the kana and CJK code points that ``name_norm`` keeps."""
    code = ord(char)
    return any(low <= code <= high for low, high in _KANA_CJK_RANGES)


def normalize_name(text: str) -> str:
    """Return the canonical ``cards.name_norm`` form of ``text``.

    The rule, verbatim from ``docs/pack-format.md`` §5.1: NFD decomposition, removal of every
    combining mark (category ``Mn``), lowercasing, then every run of characters that is neither
    ``[a-z0-9]`` nor kana/CJK replaced by a single space, trimmed.
    """
    out: list[str] = []
    for char in unicodedata.normalize("NFD", text).lower():
        if unicodedata.category(char) == "Mn":
            continue
        if "a" <= char <= "z" or "0" <= char <= "9" or _is_kana_or_cjk(char):
            out.append(char)
        elif out and out[-1] != " ":
            out.append(" ")
    return "".join(out).rstrip()


# --- rows ------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class SetRow:
    """One row of the pack's ``sets`` table (``docs/pack-format.md`` §2)."""

    set_id: str
    name: str
    series_id: str
    card_count_total: int
    card_count_official: int
    release_date: str | None
    symbol_url: str | None
    logo_url: str | None


@dataclass(frozen=True, slots=True)
class CardRow:
    """One row of the pack's ``cards`` table.

    ``image_url`` is the TCGdex image *stem* as returned by the API: the concrete asset is
    ``image_url + config.IMAGE_SUFFIX``. ``has_image`` is false when the card has no visual at
    all, which the pack builder turns into ``has_embed = 0`` (FR-3).
    """

    card_id: str
    local_id: str
    name: str
    name_norm: str
    set_id: str
    image_url: str | None
    has_image: bool


@dataclass(frozen=True, slots=True)
class CrawledSet:
    """A ``SetRow`` together with the ``CardRow`` list of the same ``set_detail`` response."""

    set_row: SetRow
    cards: tuple[CardRow, ...]


# --- crawl -----------------------------------------------------------------------------------


def _is_excluded(series_id: str) -> bool:
    """True when ``series_id`` names a series kept out of every pack (FR-20).

    The comparison is case-insensitive: ``config.EXCLUDED_SERIES`` holds casefolded ids, while
    TCGdex answers upper and mixed case ones for ``ja`` (``PMCG``, ``XYb``, ``SV``). An exclusion
    that missed on case would let TCG Pocket into a pack.
    """
    return series_id.casefold() in config.EXCLUDED_SERIES


def crawl(
    lang: str,
    *,
    client: TcgdexClient | None = None,
    max_sets: int | None = None,
) -> Iterator[CrawledSet]:
    """Yield every set of ``lang`` with its cards, excluded series left out (FR-20).

    Args:
        lang: TCGdex language code, ``fr`` or ``ja``.
        client: client to reuse; a private one is created and closed when omitted.
        max_sets: stop after that many sets — the cheap dry run of the whole chain.

    Raises:
        TcgdexUnavailable: a request failed every attempt; the release gate turns that into
            « publish nothing » (FR-22).

    A 404 on a series or a set is logged and skipped: the id came from the API itself, so it is an
    upstream inconsistency rather than data, and any material card loss is caught by the 2 % guard.
    """
    if not lang:
        raise ValueError("lang must not be empty")
    if max_sets is not None and max_sets < 0:
        raise ValueError(f"max_sets must be >= 0, got {max_sets}")

    if client is not None:
        yield from _crawl(client, lang, max_sets)
    else:
        with TcgdexClient() as owned:
            yield from _crawl(owned, lang, max_sets)


def crawl_set(
    lang: str,
    set_id: str,
    *,
    client: TcgdexClient | None = None,
) -> CrawledSet:
    """Crawl a single set — the one-set form of :func:`crawl`, for dry runs."""
    if not lang:
        raise ValueError("lang must not be empty")
    if not set_id:
        raise ValueError("set_id must not be empty")

    if client is not None:
        return _build_set(client.set_detail(lang, set_id))
    with TcgdexClient() as owned:
        return _build_set(owned.set_detail(lang, set_id))


def _crawl(client: TcgdexClient, lang: str, max_sets: int | None) -> Iterator[CrawledSet]:
    series = client.series(lang)
    kept_series = [
        series_id
        for series_id in (_text(entry.get("id")) for entry in series if isinstance(entry, dict))
        if series_id and not _is_excluded(series_id)
    ]
    log.info(
        "%s: %d series, %d kept (excluded: %s)",
        lang,
        len(series),
        len(kept_series),
        ", ".join(sorted(config.EXCLUDED_SERIES)) or "none",
    )

    seen_sets: set[str] = set()
    emitted = 0
    for series_id in kept_series:
        if max_sets is not None and emitted >= max_sets:
            return
        try:
            detail = client.series_detail(lang, series_id)
        except TcgdexNotFound:
            log.warning(
                "%s: series %s vanished between the list and its detail; skipped", lang, series_id
            )
            continue

        for brief in detail.get("sets") or []:
            if max_sets is not None and emitted >= max_sets:
                return
            if not isinstance(brief, dict):
                log.warning("%s: series %s lists a non-object set; skipped", lang, series_id)
                continue
            set_id = _text(brief.get("id"))
            if not set_id:
                log.warning("%s: series %s lists a set without an id; skipped", lang, series_id)
                continue
            if set_id in seen_sets:
                continue
            seen_sets.add(set_id)
            try:
                payload = client.set_detail(lang, set_id)
            except TcgdexNotFound:
                log.warning("%s: set %s has no detail; skipped", lang, set_id)
                continue

            crawled = _build_set(payload, fallback_series_id=series_id)
            if _is_excluded(crawled.set_row.series_id):
                # Defence in depth: the set itself claims an excluded series (FR-20).
                log.warning(
                    "%s: set %s belongs to excluded series %s; skipped",
                    lang,
                    set_id,
                    crawled.set_row.series_id,
                )
                continue
            log.info(
                "%s: set %s (%s) — %d cards, %d with a visual",
                lang,
                crawled.set_row.set_id,
                crawled.set_row.series_id,
                len(crawled.cards),
                sum(1 for card in crawled.cards if card.has_image),
            )
            yield crawled
            emitted += 1


def _build_set(payload: dict[str, Any], *, fallback_series_id: str | None = None) -> CrawledSet:
    """Turn a ``/{lang}/sets/{set_id}`` response into its ``SetRow`` and ``CardRow`` rows."""
    set_id = _text(payload.get("id"))
    if not set_id:
        raise TcgdexError("a set detail response carries no id")

    series = payload.get("serie")
    series_id = _text(series.get("id")) if isinstance(series, dict) else ""
    if not series_id:
        series_id = fallback_series_id or ""
    if not series_id:
        raise TcgdexError(f"set {set_id} carries no series id")

    counts = payload.get("cardCount")
    counts = counts if isinstance(counts, dict) else {}
    set_row = SetRow(
        set_id=set_id,
        name=_text(payload.get("name")) or set_id,
        series_id=series_id,
        card_count_total=_count(counts.get("total")),
        card_count_official=_count(counts.get("official")),
        release_date=_text(payload.get("releaseDate")) or None,
        symbol_url=_text(payload.get("symbol")) or None,
        logo_url=_text(payload.get("logo")) or None,
    )

    cards: list[CardRow] = []
    seen_cards: set[str] = set()
    for entry in payload.get("cards") or []:
        if not isinstance(entry, dict):
            log.warning("set %s lists a non-object card; skipped", set_id)
            continue
        card_id = _text(entry.get("id"))
        if not card_id:
            log.warning("set %s lists a card without an id; skipped", set_id)
            continue
        if card_id in seen_cards:
            log.warning("set %s lists card %s twice; the duplicate is skipped", set_id, card_id)
            continue
        seen_cards.add(card_id)
        name = _text(entry.get("name"))
        image_url = _text(entry.get("image")) or None
        cards.append(
            CardRow(
                card_id=card_id,
                local_id=_text(entry.get("localId")),
                name=name,
                name_norm=normalize_name(name),
                set_id=set_id,
                image_url=image_url,
                has_image=image_url is not None,
            )
        )

    return CrawledSet(set_row=set_row, cards=tuple(cards))


def _text(value: Any) -> str:
    """Coerce a JSON scalar to a trimmed string; absent or null becomes ``""``."""
    if value is None:
        return ""
    return str(value).strip()


def _count(value: Any) -> int:
    """Coerce a ``cardCount`` member to a non-negative int; anything odd becomes ``0``."""
    try:
        count = int(value)
    except (TypeError, ValueError):
        return 0
    return count if count >= 0 else 0


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline.catalog",
        description="Crawl the TCGdex catalogue of one language and print its card rows.",
    )
    parser.add_argument("--lang", required=True, help="language code to crawl, e.g. fr or ja")
    parser.add_argument(
        "--max-sets",
        type=int,
        default=None,
        metavar="N",
        help="stop after N sets (dry run); the whole language is crawled when omitted",
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    crawled_sets: list[SetRow] = []
    card_total = 0
    image_total = 0
    try:
        for crawled in crawl(args.lang, max_sets=args.max_sets):
            crawled_sets.append(crawled.set_row)
            print(
                f"# set {crawled.set_row.set_id}\t{crawled.set_row.series_id}\t"
                f"{crawled.set_row.card_count_official}/{crawled.set_row.card_count_total}\t"
                f"{crawled.set_row.name}"
            )
            for card in crawled.cards:
                card_total += 1
                image_total += int(card.has_image)
                print(
                    f"{card.card_id}\t{card.local_id}\t{card.set_id}\t"
                    f"{'image' if card.has_image else 'no-image'}\t{card.name}\t{card.name_norm}"
                )
    except TcgdexError as exc:
        log.error("%s", exc)
        return 1

    offenders = sorted(row.set_id for row in crawled_sets if _is_excluded(row.series_id))
    print(
        f"{args.lang}: {len(crawled_sets)} sets, {card_total} cards, {image_total} with a visual; "
        f"sets from an excluded series: {offenders or 'none'}"
    )
    return 1 if offenders else 0


if __name__ == "__main__":
    sys.exit(_main())
