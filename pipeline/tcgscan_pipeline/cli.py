"""Command line of the pack pipeline.

    python -m tcgscan_pipeline build [--langs fr,ja] [--out dist] [--max-sets N]
                                     [--en-image-fallback] [--previous-manifest FILE]

``build`` runs crawl -> images -> embed -> pack (:func:`tcgscan_pipeline.packbuild.build_pack`, once
per language), then writes ``manifest.json`` and passes the release gate
(:func:`tcgscan_pipeline.guard.check`).

The build is **all-or-nothing** (FR-22): every artefact is first written to a private staging
directory next to ``--out``. Only when the gate opens are the packs and the manifest moved into
``--out``; on any failure ``--out`` is left empty and the exit status is non-zero, so the previously
published release stays in place.
"""

from __future__ import annotations

import argparse
import contextlib
import logging
import shutil
import sqlite3
import sys
import tempfile
from collections.abc import Sequence
from pathlib import Path
from typing import Any

from . import config, guard, manifest
from .embed import Embedder, EmbedError
from .embed_cache import EmbeddingCache
from .guard import GuardError
from .images import ImageCache
from .manifest import ManifestError
from .packbuild import PackBuild, PackBuildError, build_pack
from .tcgdex import TcgdexClient, TcgdexError, TcgdexUnavailable

log = logging.getLogger(__name__)

_PACK_GLOB = "pack-*.db.gz"


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point; returns the process exit status."""
    parser = _parser()
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # Only one command today; the dispatch stays here so a second one is a one-line addition.
    return _run_build(args)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline", description="TCG Scan language pack pipeline."
    )
    commands = parser.add_subparsers(dest="command", required=True, metavar="command")
    build = commands.add_parser(
        "build",
        help="crawl, embed, pack, write manifest.json and pass the release gate",
        description="Build the language packs and manifest.json into --out, all or nothing.",
    )
    build.add_argument(
        "--langs",
        type=_langs,
        default=config.LANGUAGES,
        metavar="LIST",
        help="comma-separated language codes (default: %(default)s)",
    )
    build.add_argument(
        "--out",
        type=Path,
        default=config.DEFAULT_OUT_DIR,
        metavar="DIR",
        help=f"output directory (default {config.DEFAULT_OUT_DIR}); empty on failure",
    )
    build.add_argument(
        "--max-sets",
        type=_positive_int,
        default=None,
        metavar="N",
        help="stop after N sets per language (dry run); every set is built when omitted",
    )
    build.add_argument(
        "--en-image-fallback",
        action="store_true",
        help="borrow the EN visual of a card that has none in its language (D15/FR-4, off by "
        "default); the rows it produces are tagged has_embed=2",
    )
    build.add_argument(
        "--previous-manifest",
        type=Path,
        default=None,
        metavar="FILE",
        help=f"compare with this local manifest instead of fetching the latest release "
        f"({config.REPO_ENV_VAR}); for dry runs of the guard",
    )
    return parser


def _langs(value: str) -> tuple[str, ...]:
    """Parse ``--langs``: comma-separated, each code valid as a manifest key, no duplicate."""
    langs: list[str] = []
    for part in value.split(","):
        lang = part.strip()
        if not manifest.is_valid_lang(lang):
            raise argparse.ArgumentTypeError(f"invalid language code {lang!r} in {value!r}")
        if lang not in langs:
            langs.append(lang)
    return tuple(langs)


def _positive_int(value: str) -> int:
    try:
        number = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError(f"not an integer: {value!r}") from None
    if number < 1:
        raise argparse.ArgumentTypeError(f"must be >= 1, got {number}")
    return number


def _run_build(args: argparse.Namespace) -> int:
    try:
        _build(
            tuple(args.langs),
            Path(args.out),
            max_sets=args.max_sets,
            en_image_fallback=args.en_image_fallback,
            previous_manifest=args.previous_manifest,
        )
    except GuardError as exc:
        for reason in exc.reasons:
            log.error("guard: %s", reason)
        log.error("release gate closed, nothing emitted into %s", args.out)
        return 1
    # ValueError included: a bad argument that slipped past argparse is an operator mistake, and a
    # traceback is not how a command line reports one.
    except (
        ManifestError,
        PackBuildError,
        EmbedError,
        TcgdexError,
        ValueError,
        OSError,
        sqlite3.Error,
    ) as exc:
        log.error("build failed, nothing emitted into %s: %s", args.out, exc)
        return 1
    return 0


def _build(
    langs: tuple[str, ...],
    out_dir: Path,
    *,
    max_sets: int | None,
    en_image_fallback: bool,
    previous_manifest: Path | None,
) -> None:
    """Build every pack, pass the gate, then publish into ``out_dir``; raise on any failure."""
    out = out_dir.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # A stale artefact of an earlier run must never be mistaken for this run's output (FR-22).
    _clear_artifacts(out)

    # Read the reference first: no point in a long crawl if the guard could not be evaluated.
    previous = _previous(previous_manifest)

    # The staging directory is a sibling of ``out`` so that moving into it stays on one filesystem,
    # and never inside it: a killed process must not leave debris in the directory CI uploads.
    staging = Path(tempfile.mkdtemp(prefix=f".{out.name}-staging-", dir=out.parent))
    try:
        built, unavailable = _build_packs(
            langs, staging, max_sets=max_sets, en_image_fallback=en_image_fallback
        )
        new_manifest = manifest.build_manifest(built)
        guard.check(previous, new_manifest, unavailable=unavailable)
        manifest.write_manifest(new_manifest, staging / config.MANIFEST_FILENAME)
        _publish(staging, out)
    finally:
        shutil.rmtree(staging, ignore_errors=True)

    log.info("release gate open: %s", ", ".join(_summary(lang, b) for lang, b in built.items()))
    log.info("emitted into %s: %s", out, ", ".join(sorted(p.name for p in out.iterdir())))


def _previous(local_file: Path | None) -> dict[str, Any] | None:
    if local_file is not None:
        log.info("comparing with the local manifest %s", local_file)
        return manifest.load_previous(local_file)
    repo = manifest.resolve_repo()
    if repo == config.GITHUB_REPO_PLACEHOLDER:
        # Nothing can have been published to the placeholder: do not ask GitHub about a stranger's
        # repository, and say loudly that the 2 % guard has nothing to compare with.
        log.warning(
            "%s is not set: no previous manifest to compare with, the 2 %% guard is not applied",
            config.REPO_ENV_VAR,
        )
        return None
    log.info("fetching the previous manifest of %s", repo)
    return manifest.fetch_previous(repo)


def _build_packs(
    langs: tuple[str, ...], staging: Path, *, max_sets: int | None, en_image_fallback: bool
) -> tuple[dict[str, PackBuild], list[str]]:
    """Build the pack of each language into ``staging``.

    Returns the packs built and the languages whose crawl raised ``TcgdexUnavailable``. The first
    unavailable crawl stops the run: the gate is going to refuse the release anyway, so the
    remaining languages would only burn CI minutes.
    """
    built: dict[str, PackBuild] = {}
    unavailable: list[str] = []
    with contextlib.ExitStack() as stack:
        # One client, one model, one cache for all languages: the rate limiter then holds across
        # them and the 24 MB graph is loaded once.
        client = stack.enter_context(TcgdexClient())
        embedder = Embedder()
        images = stack.enter_context(ImageCache())
        cache = stack.enter_context(EmbeddingCache(model_sha256=embedder.model_sha256))
        for position, lang in enumerate(langs, start=1):
            log.info("[%d/%d] building the %s pack", position, len(langs), lang)
            try:
                built[lang] = build_pack(
                    lang,
                    staging,
                    max_sets=max_sets,
                    en_image_fallback=en_image_fallback,
                    client=client,
                    embedder=embedder,
                    images=images,
                    cache=cache,
                )
            except TcgdexUnavailable as exc:
                log.error("%s: %s", lang, exc)
                unavailable.append(lang)
                break
            log.info("[%d/%d] %s", position, len(langs), _summary(lang, built[lang]))
    return built, unavailable


def _summary(lang: str, built: PackBuild) -> str:
    return f"{lang}: {built.card_count} cards, {built.gz_bytes} B"


def _clear_artifacts(out: Path) -> None:
    """Delete the pack archives and the manifest left in ``out`` by an earlier run."""
    stale = [*out.glob(_PACK_GLOB), out / config.MANIFEST_FILENAME]
    for path in stale:
        path.unlink(missing_ok=True)


def _publish(staging: Path, out: Path) -> None:
    """Move the staged artefacts into ``out``, manifest last; undo all of it if one move fails."""
    names = sorted(p.name for p in staging.glob(_PACK_GLOB))
    names.append(config.MANIFEST_FILENAME)
    moved: list[Path] = []
    try:
        for name in names:
            target = out / name
            shutil.move(str(staging / name), str(target))
            moved.append(target)
    except BaseException:
        for target in moved:
            target.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    sys.exit(main())
