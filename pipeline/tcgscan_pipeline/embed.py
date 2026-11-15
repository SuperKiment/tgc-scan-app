"""Visual embedding stage.

Runs **the same ONNX file the app ships**, ``app/src/main/assets/models/embed_dinov2s_int8.onnx``
(int8 export of ``facebook/dinov2-small``), with the preprocessing and int8 encoding of
``docs/pack-format.md`` §3 — which is authoritative and must stay identical to
``scan/Embedder.kt`` + ``scan/Preprocess.kt`` on the device, otherwise pack vectors and on-device
vectors stop being comparable. The model's sha256 is verified against ``tools/models.lock`` before
the session is created, so a truncated or substituted asset fails the build loudly instead of
producing silently wrong vectors.

Unchanged cards are never re-embedded: :class:`~tcgscan_pipeline.embed_cache.EmbeddingCache` keys
every vector by ``(card_id, lang, image_sha256, model_sha256)`` (FR-21).

Cards without a visual in their own language yield no vector at all (``has_embed = 0``, FR-3),
unless an optional source is enabled, both **off by default**: the other-language fallback of
D15/FR-4 (``config.FALLBACK_IMAGE_LANGS``) tags the rows it produces ``has_embed = 2`` so the app
can refuse to pre-check them, while the Limitless
TCG source of JA visuals (``--jp-extra-images``, :mod:`tcgscan_pipeline.limitless`) is the card's
own JP print and so keeps ``has_embed = 1``.

Dry runs:

    python -m tcgscan_pipeline.embed --image pipeline/.cache/images/fr/base1-1.webp
    python -m tcgscan_pipeline.embed --lang fr --set base1 --max-cards 2
    python -m tcgscan_pipeline.embed --lang ja --set SM1M --max-cards 3 --jp-extra-images
"""

from __future__ import annotations

import argparse
import hashlib
import logging
import math
import stat
import sys
from dataclasses import dataclass
from pathlib import Path
from types import TracebackType

import cv2
import numpy as np
import onnxruntime as ort

from . import config
from .catalog import TCGDEX_ASSET_PREFIX, CardRow, crawl_set
from .embed_cache import EmbeddingCache
from .images import ImageCache
from .limitless import LIMITLESS_LANG, LimitlessSource
from .tcgdex import TcgdexClient, TcgdexError, TcgdexNotFound

log = logging.getLogger(__name__)

IMAGE_SIZE = 224
"""Side of the square input tensor; the aspect ratio is squashed, no letterbox (§3.1)."""

IMAGENET_MEAN = np.array((0.485, 0.456, 0.406), dtype=np.float32)
IMAGENET_STD = np.array((0.229, 0.224, 0.225), dtype=np.float32)

DEFAULT_INTRA_OP_THREADS = 2
"""Threads per ONNX Runtime session; 2 is the configuration measured in docs/models.md §1."""

HAS_EMBED_NONE = 0
HAS_EMBED_OWN_IMAGE = 1
HAS_EMBED_FALLBACK_IMAGE = 2
"""``cards.has_embed`` values of ``docs/pack-format.md`` §2.2."""

_SHA_CHUNK_BYTES = 1 << 20


class EmbedError(RuntimeError):
    """A local embedding failure: missing or mismatched model, unreadable image, odd output."""


# --- model identity ---------------------------------------------------------------------------


def default_model_path() -> Path:
    """Path of the model asset shared with the app."""
    return config.MODELS_DIR / config.EMBED_MODEL_ASSET


def file_sha256(path: Path) -> str:
    """Hex sha256 of a file, read in chunks (the model is ~24 MB)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while chunk := handle.read(_SHA_CHUNK_BYTES):
            digest.update(chunk)
    return digest.hexdigest()


def verify_model(path: Path | None = None, *, lock_path: Path | None = None) -> str:
    """Check the model asset against ``tools/models.lock`` and return its sha256.

    Raises:
        EmbedError: the asset is missing, the lock file does not pin it, the lock disagrees with
            ``config.EMBED_MODEL_SHA256``, or the bytes on disk do not match.
    """
    model = Path(path) if path is not None else default_model_path()
    if not model.is_file():
        raise EmbedError(f"{model} is missing; run `bash tools/fetch_models.sh` first")

    expected_sha, expected_bytes = _lock_entry(model.name, lock_path)
    if model.name == config.EMBED_MODEL_ASSET and expected_sha != config.EMBED_MODEL_SHA256:
        raise EmbedError(
            f"tools/models.lock pins {model.name} to {expected_sha} but "
            f"config.EMBED_MODEL_SHA256 is {config.EMBED_MODEL_SHA256}; "
            "docs/models.md, tools/models.lock and config.py must agree"
        )

    actual_bytes = model.stat().st_size
    if actual_bytes != expected_bytes:
        raise EmbedError(
            f"{model} is {actual_bytes} B, tools/models.lock pins {expected_bytes} B; "
            "re-run `bash tools/fetch_models.sh`"
        )
    actual_sha = file_sha256(model)
    if actual_sha != expected_sha:
        raise EmbedError(
            f"{model} has sha256 {actual_sha}, tools/models.lock pins {expected_sha}; "
            "re-run `bash tools/fetch_models.sh`"
        )
    return actual_sha


def _lock_entry(asset: str, lock_path: Path | None) -> tuple[str, int]:
    """Return ``(sha256, bytes)`` pinned for ``asset`` by the lock file."""
    lock = Path(lock_path) if lock_path is not None else config.MODELS_LOCK
    if not lock.is_file():
        raise EmbedError(f"{lock} is missing; it pins every model asset and must be committed")

    for number, raw in enumerate(lock.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) < 3:
            raise EmbedError(f"{lock}:{number} is not `<asset> <sha256> <bytes> <url>`")
        if fields[0] != asset:
            continue
        try:
            return fields[1], int(fields[2])
        except ValueError as exc:
            raise EmbedError(f"{lock}:{number} has a non-numeric byte count") from exc

    raise EmbedError(f"{lock} does not pin {asset}")


# --- preprocessing (docs/pack-format.md §3.1) -------------------------------------------------


def load_image(path: Path) -> np.ndarray:
    """Read an image file as an RGB ``uint8`` array."""
    image = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if image is None:
        raise EmbedError(f"{path} is not a readable image")
    return cv2.cvtColor(image, cv2.COLOR_BGR2RGB)


def preprocess(rgb: np.ndarray) -> np.ndarray:
    """Turn an RGB image into the ``pixel_values`` tensor of shape ``(1, 3, 224, 224)``.

    Verbatim §3.1: ``cv2.resize`` with ``INTER_LINEAR`` to 224×224 (aspect squashed, no letterbox,
    no centre crop), ``float32`` divided by 255, ImageNet mean/std per RGB channel, then NCHW.
    """
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError(f"expected an RGB HxWx3 array, got shape {rgb.shape}")

    resized = cv2.resize(rgb, (IMAGE_SIZE, IMAGE_SIZE), interpolation=cv2.INTER_LINEAR)
    scaled = resized.astype(np.float32) / 255.0
    normalised = (scaled - IMAGENET_MEAN) / IMAGENET_STD
    return np.ascontiguousarray(normalised.transpose(2, 0, 1)[np.newaxis, ...], dtype=np.float32)


# --- int8 encoding (docs/pack-format.md §3.3) -------------------------------------------------


def quantize_int8(vec: np.ndarray) -> bytes:
    """Encode a L2-normalised vector as ``config.EMBED_DIM`` signed bytes.

    ``round(v_i * 127)`` clipped to ``[-127, 127]``, in component order — the exact content of
    ``embeddings.vec``, so the app's integer dot product divided by ``127²`` is a cosine. Rounding
    is numpy's round-half-to-even, as in Python's ``round()``.
    """
    flat = np.asarray(vec, dtype=np.float32).reshape(-1)
    if flat.size != config.EMBED_DIM:
        raise ValueError(f"vec must have {config.EMBED_DIM} components, got {flat.size}")
    if not np.isfinite(flat).all():
        raise ValueError("vec carries a non-finite component")

    scale = config.EMBED_QUANT_SCALE
    quantised = np.clip(np.rint(flat * scale), -scale, scale)
    return quantised.astype(np.int8).tobytes()


# --- embedder ---------------------------------------------------------------------------------


class Embedder:
    """The ONNX session of the shared model, plus the §3 pooling.

    The session is created once and reused for every card: loading the 24 MB graph dominates the
    cost of a single inference.
    """

    def __init__(
        self,
        model_path: Path | None = None,
        *,
        lock_path: Path | None = None,
        threads: int = DEFAULT_INTRA_OP_THREADS,
    ) -> None:
        if threads < 1:
            raise ValueError(f"threads must be >= 1, got {threads}")
        self.model_path = Path(model_path) if model_path is not None else default_model_path()
        self.model_sha256 = verify_model(self.model_path, lock_path=lock_path)

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        self._session = ort.InferenceSession(
            str(self.model_path), sess_options=options, providers=["CPUExecutionProvider"]
        )

        inputs = self._session.get_inputs()
        if len(inputs) != 1:
            raise EmbedError(
                f"{self.model_path.name} takes {len(inputs)} inputs, expected only pixel_values"
            )
        self._input_name = inputs[0].name
        self._output_name = self._session.get_outputs()[0].name
        log.info(
            "embedding model %s (%s) loaded: %s -> %s",
            self.model_path.name,
            self.model_sha256[:12],
            self._input_name,
            self._output_name,
        )

    @property
    def model_id(self) -> str:
        """Value of ``pack_meta.embed_model``: asset name and sha256 (§2.1)."""
        return f"{self.model_path.name}@{self.model_sha256}"

    def embed_image(self, path: Path) -> np.ndarray:
        """Return the L2-normalised CLS embedding of one image file.

        Raises:
            EmbedError: the file is unreadable, or the model returned an unexpected shape or a
                degenerate (zero / non-finite) vector.
        """
        tensor = preprocess(load_image(path))
        outputs = self._session.run([self._output_name], {self._input_name: tensor})
        hidden = np.asarray(outputs[0])
        if hidden.ndim != 3 or hidden.shape[0] != 1 or hidden.shape[2] != config.EMBED_DIM:
            raise EmbedError(
                f"{self._output_name} has shape {hidden.shape}, "
                f"expected (1, tokens, {config.EMBED_DIM})"
            )

        cls_token = hidden[0, 0, :].astype(np.float32)
        norm = float(np.linalg.norm(cls_token))
        if not math.isfinite(norm) or norm <= 0.0:
            raise EmbedError(f"the model returned a degenerate vector for {path} (L2 norm {norm})")
        return cls_token / norm

    def embed_int8(self, path: Path) -> bytes:
        """Embed one image file straight into its ``embeddings.vec`` blob."""
        return quantize_int8(self.embed_image(path))


# --- per-card stage ---------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class EmbedResult:
    """The embedding of one card, ready for the ``embeddings`` table."""

    card_id: str
    vec: bytes
    has_embed: int
    image_path: Path
    reused: bool
    """True when the vector came from the cross-run cache instead of an ONNX run (FR-21)."""
    from_limitless: bool = False
    """True when the visual is the Limitless TCG one of a JA card TCGdex has no image for."""


class FallbackImageResolver:
    """Image stem of the same card id in another TCGdex language — the ``--en-image-fallback`` hook.

    One ``GET {lang}/sets/{set_id}`` per ``(lang, set)`` and run, memoised, builds the
    ``card_id -> stem`` map of that language's print of the set: the per-card request that
    ``docs/pack-format.md`` §4.4 forbids is never made. Sets are fetched lazily, only for a card
    that has no visual in its own language and only when the operator opted in.
    """

    def __init__(self, *, client: TcgdexClient | None = None) -> None:
        self._owns_client = client is None
        self._client = client or TcgdexClient()
        self._stems: dict[tuple[str, str], dict[str, str]] = {}

    def close(self) -> None:
        """Close the underlying client when this resolver created it."""
        if self._owns_client:
            self._client.close()

    def __enter__(self) -> "FallbackImageResolver":
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.close()

    def stem(self, lang: str, set_id: str, card_id: str) -> str | None:
        """Return the ``lang`` image stem of ``card_id`` (of set ``set_id``), or ``None``.

        Raises:
            TcgdexUnavailable: every attempt failed; the release gate turns that into « publish
                nothing » (FR-22).
        """
        return self._set_stems(lang, set_id).get(card_id)

    def _set_stems(self, lang: str, set_id: str) -> dict[str, str]:
        key = (lang, set_id)
        stems = self._stems.get(key)
        if stems is not None:
            return stems

        stems = {}
        try:
            # ``set_detail`` percent-encodes both segments, ``/`` included, so an API-provided id
            # stays one path segment.
            payload = self._client.set_detail(lang, set_id)
        except TcgdexNotFound:
            log.info("%s/%s: no such set in TCGdex; no fallback visuals from it", lang, set_id)
        else:
            for entry in payload.get("cards") or []:
                if not isinstance(entry, dict):
                    continue
                card_id = str(entry.get("id") or "").strip()
                stem = str(entry.get("image") or "").strip()
                # Same rule as the crawl (§4.5): a stem outside the TCGdex asset prefix is no visual.
                if card_id and stem.startswith(TCGDEX_ASSET_PREFIX):
                    stems.setdefault(card_id, stem)
        self._stems[key] = stems
        return stems


def _is_cached_visual(path: Path) -> bool:
    """True when ``path`` already holds a usable visual, by ``ImageCache``'s own rule.

    Mirrors ``images.is_cached``, symlink rejection included: the cache tree comes back from
    ``actions/cache``, so a symlink there counts as absent instead of being embedded through to
    wherever it points.
    """
    try:
        info = path.lstat()
    except OSError:
        return False
    return stat.S_ISREG(info.st_mode) and info.st_size > 0


def embed_card(
    card: CardRow,
    lang: str,
    *,
    embedder: Embedder,
    images: ImageCache,
    cache: EmbeddingCache | None = None,
    fallback_resolver: FallbackImageResolver | None = None,
    limitless: LimitlessSource | None = None,
) -> EmbedResult | None:
    """Embed one crawled card, reusing the caches, or return ``None`` when it has no visual.

    Args:
        card: the row produced by :mod:`tcgscan_pipeline.catalog`.
        lang: language of the pack being built; part of the cache key.
        embedder: the loaded model.
        images: visual cache; the image is downloaded only if it is not on disk yet.
        cache: cross-run embedding cache (FR-21); ``None`` disables reuse.
        fallback_resolver: other-language fallback (D15/FR-4), trying
            ``config.FALLBACK_IMAGE_LANGS`` in order and skipping the card's own language.
            ``None`` — the default — means the fallback is off, so a card without its own visual
            simply gets no vector (``has_embed = 0``, FR-3).
        limitless: Limitless TCG source, used only for ``ja`` cards TCGdex has no image for and
            tried before the fallback. ``None`` — the default — means it is off. Its visual is
            the card's own JP print, hence ``has_embed = 1``; it never raises.

    Returns:
        The ``EmbedResult`` with ``has_embed = 1`` for the card's own visual (TCGdex or Limitless)
        and ``2`` for an other-language one, or ``None`` when no visual could be obtained.

    Raises:
        TcgdexUnavailable: a download or a fallback set lookup failed every attempt.
        EmbedError: the cached file is not a readable image, or the model misbehaved.
    """
    if not lang:
        raise ValueError("lang must not be empty")

    path = images.fetch(lang, card.card_id, card.image_url)
    has_embed = HAS_EMBED_OWN_IMAGE
    if path is None and limitless is not None and lang == LIMITLESS_LANG:
        visual = limitless.fetch(card, images)
        if visual is not None:
            try:
                return _embed_path(
                    card, lang, visual, HAS_EMBED_OWN_IMAGE, True, embedder=embedder, cache=cache
                )
            except EmbedError as exc:
                # The source is optional: a bad file (e.g. cached by an earlier run) must neither
                # abort the build nor stay in the cache; the card goes on to the next source.
                log.warning("%s: Limitless visual unusable (%s); removed", card.card_id, exc)
                visual.unlink(missing_ok=True)
    if path is None and fallback_resolver is not None:
        path = _fallback_visual(card, lang, images, fallback_resolver)
        has_embed = HAS_EMBED_FALLBACK_IMAGE
    if path is None:
        return None
    return _embed_path(card, lang, path, has_embed, False, embedder=embedder, cache=cache)


def _fallback_visual(
    card: CardRow, lang: str, images: ImageCache, resolver: FallbackImageResolver
) -> Path | None:
    """First visual of ``card``'s id in ``config.FALLBACK_IMAGE_LANGS``, own language excluded."""
    langs = [other for other in config.FALLBACK_IMAGE_LANGS if other != lang]
    # The set lookup is a request: FR-21 wants it skipped whenever a visual of an earlier run is
    # still cached, so every cache is read before the first request.
    for other in langs:
        cached = images.path_for(other, card.card_id)
        if _is_cached_visual(cached):
            return cached
    for other in langs:
        path = images.fetch(other, card.card_id, resolver.stem(other, card.set_id, card.card_id))
        if path is not None:
            return path
    return None


def _embed_path(
    card: CardRow,
    lang: str,
    path: Path,
    has_embed: int,
    from_limitless: bool,
    *,
    embedder: Embedder,
    cache: EmbeddingCache | None,
) -> EmbedResult:
    """Embed the visual at ``path`` through the cross-run cache (FR-21)."""
    image_sha256 = file_sha256(path)
    if cache is not None:
        cached = cache.get(card.card_id, lang, image_sha256)
        if cached is not None:
            return EmbedResult(
                card_id=card.card_id,
                vec=cached,
                has_embed=has_embed,
                image_path=path,
                reused=True,
                from_limitless=from_limitless,
            )

    vec = embedder.embed_int8(path)
    if cache is not None:
        cache.put(card.card_id, lang, image_sha256, vec)
    return EmbedResult(
        card_id=card.card_id,
        vec=vec,
        has_embed=has_embed,
        image_path=path,
        reused=False,
        from_limitless=from_limitless,
    )


# --- dry runs ---------------------------------------------------------------------------------


def _main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="python -m tcgscan_pipeline.embed",
        description="Embed one image file, or the cached visuals of one set, with the app's model.",
    )
    parser.add_argument(
        "--image",
        type=Path,
        default=None,
        metavar="PATH",
        help="embed this single file and print its 384 components and their L2 norm",
    )
    parser.add_argument("--lang", default=None, help="language code of the set mode, e.g. fr or ja")
    parser.add_argument("--set", default=None, metavar="SET_ID", help="set id, e.g. base1")
    parser.add_argument(
        "--max-cards",
        type=int,
        default=None,
        metavar="N",
        help="stop after N cards of the set; the whole set is embedded when omitted",
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
    args = parser.parse_args(argv)

    if (args.image is None) == (args.lang is None and args.set is None):
        parser.error("pass either --image PATH or both --lang and --set")
    if args.image is None and (not args.lang or not args.set):
        parser.error("the set mode needs both --lang and --set")

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    try:
        embedder = Embedder()
        if args.image is not None:
            return _embed_one(embedder, args.image)
        return _embed_set(
            embedder,
            args.lang,
            args.set,
            args.max_cards,
            en_fallback=args.en_image_fallback,
            jp_extra_images=args.jp_extra_images,
        )
    except (EmbedError, TcgdexError) as exc:
        log.error("%s", exc)
        return 1


def _embed_one(embedder: Embedder, image: Path) -> int:
    """Print the vector of a single file — the cheap end-to-end check of the §3 contract."""
    vec = embedder.embed_image(image)
    print(" ".join(f"{value:.6f}" for value in vec))
    print(
        f"dim={vec.size} l2={float(np.linalg.norm(vec)):.6f} "
        f"int8_bytes={len(quantize_int8(vec))} model={embedder.model_id}"
    )
    return 0


def _embed_set(
    embedder: Embedder,
    lang: str,
    set_id: str,
    max_cards: int | None,
    *,
    en_fallback: bool,
    jp_extra_images: bool,
) -> int:
    """Embed one crawled set through both caches and print one line per card."""
    crawled = crawl_set(lang, set_id)
    cards = crawled.cards if max_cards is None else crawled.cards[:max_cards]
    resolver = FallbackImageResolver() if en_fallback else None
    limitless = LimitlessSource() if jp_extra_images else None
    embedded = 0
    from_limitless = 0
    try:
        with EmbeddingCache(model_sha256=embedder.model_sha256) as cache, ImageCache() as images:
            for card in cards:
                result = embed_card(
                    card,
                    lang,
                    embedder=embedder,
                    images=images,
                    cache=cache,
                    fallback_resolver=resolver,
                    limitless=limitless,
                )
                if result is None:
                    print(f"{card.card_id}\thas_embed={HAS_EMBED_NONE}\tno-image")
                    continue
                embedded += 1
                from_limitless += int(result.from_limitless)
                source = "limitless" if result.from_limitless else "tcgdex"
                print(
                    f"{result.card_id}\thas_embed={result.has_embed}\t{source}\t"
                    f"{'reused' if result.reused else 'computed'}\t{len(result.vec)} B"
                )
            print(
                f"{lang}/{set_id}: {embedded}/{len(cards)} cards embedded "
                f"({from_limitless} Limitless); {cache.summary()}"
            )
    finally:
        if resolver is not None:
            resolver.close()
        if limitless is not None:
            limitless.close()
    return 0


if __name__ == "__main__":
    sys.exit(_main())
