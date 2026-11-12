"""Release gate: the 2 % guard (FR-22, ``docs/pack-format.md`` §7).

:func:`check` compares the freshly built manifest with the previously published one and refuses the
release when a language lost more than 2 % of its cards, when a language of the previous release is
missing, or when a crawl gave up with ``TcgdexUnavailable``. It also refuses a new manifest the app
would reject. The caller (:mod:`tcgscan_pipeline.cli`) emits nothing into ``dist/`` on failure, so
the previous release stays in place.
"""

from __future__ import annotations

import logging
from collections.abc import Collection, Mapping
from typing import Any

from . import config
from .manifest import card_counts, validation_errors

log = logging.getLogger(__name__)


class GuardError(RuntimeError):
    """The release gate is closed; ``reasons`` lists every rule that failed."""

    def __init__(self, reasons: list[str]) -> None:
        super().__init__("release gate closed: " + "; ".join(reasons))
        self.reasons = reasons


def check(
    prev: Mapping[str, Any] | None,
    new: Mapping[str, Any],
    *,
    unavailable: Collection[str] = (),
) -> None:
    """Open the gate or raise.

    Args:
        prev: the previously published manifest, ``None`` on the very first run (nothing to
            compare with, only the checks on ``new`` apply).
        new: the manifest about to be published.
        unavailable: languages whose crawl raised ``TcgdexUnavailable``.

    Raises:
        GuardError: at least one rule failed; every failing rule is listed.
        ManifestError: ``prev`` or ``new`` has no usable ``cardCount`` to compare.
    """
    reasons = [f"{lang}: TCGdex unavailable during the crawl" for lang in sorted(unavailable)]
    reasons.extend(validation_errors(new))

    if prev is not None:
        previous_counts = card_counts(prev, "previous manifest")
        new_counts = _lenient_card_counts(new)
        for lang, before in sorted(previous_counts.items()):
            after = new_counts.get(lang)
            if after is None:
                reasons.append(f"{lang}: pack missing from the new manifest")
                continue
            loss = (before - after) / before
            log.info("%s: %d -> %d cards (%+.2f %%)", lang, before, after, -loss * 100)
            if loss > config.MAX_CARD_LOSS_RATIO:
                reasons.append(
                    f"{lang}: {before} -> {after} cards, a loss of {loss * 100:.2f} % "
                    f"(limit {config.MAX_CARD_LOSS_RATIO * 100:.0f} %)"
                )

    if reasons:
        raise GuardError(reasons)


def _lenient_card_counts(manifest: Mapping[str, Any]) -> dict[str, int]:
    """``{language: cardCount}`` of the entries that carry one.

    Unlike :func:`card_counts` this never raises: a malformed entry of the new manifest is already
    reported by ``validation_errors``, and must not hide the loss check of the other languages.
    """
    packs = manifest.get("packs")
    if not isinstance(packs, Mapping):
        return {}
    return {
        str(lang): entry["cardCount"]
        for lang, entry in packs.items()
        if isinstance(entry, Mapping)
        and isinstance(entry.get("cardCount"), int)
        and not isinstance(entry["cardCount"], bool)
    }
