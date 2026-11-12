"""Static configuration of the pack pipeline.

Every value that the pipeline and the Android app must agree on is documented in
``docs/pack-format.md``; this module is the single Python-side copy of it. Nothing here reads the
network or the filesystem at import time.
"""

from __future__ import annotations

from pathlib import Path

# --- TCGdex API (docs/pack-format.md §4) ------------------------------------------------------

API_BASE = "https://api.tcgdex.net/v2"
"""Base URL of the TCGdex REST API. Paths are appended as ``/{lang}/series`` and friends."""

LANGUAGES: tuple[str, ...] = ("fr", "ja")
"""Language codes of the published packs. TCGdex uses ``ja`` (``/v2/jp/...`` answers 404)."""

EXCLUDED_SERIES: frozenset[str] = frozenset({"tcgp"})
"""Series excluded from every pack (FR-20). TCG Pocket is a digital game: excluded by series id,
never by a frozen set list."""

IMAGE_SUFFIX = "/low.webp"
"""Suffix appended to a card's TCGdex image stem to get the concrete asset to download."""

USER_AGENT = "tcg-scan-app pack pipeline (+https://tcgdex.dev)"

# --- Rate limiting and retries (FR-21, docs/pack-format.md §4.6-§4.7) -------------------------

MIN_REQUEST_INTERVAL_S = 0.150
"""Minimum delay between two TCGdex requests."""

MAX_RETRIES = 5
"""Attempts per request before raising ``TcgdexUnavailable``."""

RETRY_BACKOFF_BASE_S = 1.0
"""First backoff delay; attempt *n* waits ``BASE * 2 ** (n - 1)``, capped by the value below."""

RETRY_BACKOFF_MAX_S = 30.0

REQUEST_TIMEOUT_S = 30.0
"""Per-request socket/read timeout."""

# --- Embedding model (docs/models.md §1, docs/pack-format.md §3) ------------------------------

EMBED_MODEL_ASSET = "embed_dinov2s_int8.onnx"
"""Asset name, inside ``app/src/main/assets/models/``, of the model shared with the app."""

EMBED_MODEL_SHA256 = "c179f8f7f592449c4c1bca4cd124a7538021428c5ffb89afde9503935b197efb"

EMBED_DIM = 384
"""Components per embedding vector; ``embeddings.vec`` is exactly this many int8 bytes."""

EMBED_QUANT_SCALE = 127
"""int8 encoding of a L2-normalised component: ``round(v * 127)`` clipped to ``[-127, 127]``."""

# --- Artefact schemas (docs/pack-format.md §2.1, §6) -----------------------------------------

PACK_SCHEMA = 1
"""Value written to ``pack_meta.pack_schema``."""

MANIFEST_SCHEMA = 1
"""Value written to ``manifest.json``'s ``schema`` field."""

# --- Release gate (FR-22, docs/pack-format.md §7) --------------------------------------------

MAX_CARD_LOSS_RATIO = 0.02
"""A language losing more than 2 % of its cards against the published manifest fails the gate."""

# --- Published manifest (docs/pack-format.md §6) ---------------------------------------------

MANIFEST_FILENAME = "manifest.json"
"""Release asset name of the manifest, in ``dist/`` and under ``releases/latest/download/``."""

REPO_ENV_VAR = "TCGSCAN_REPO"
"""Environment variable holding the ``owner/repo`` whose latest release the guard compares with."""

GITHUB_REPO_PLACEHOLDER = "CHANGE-ME-OWNER/tcg-scan-app"
"""Same placeholder as ``AppConfig.GITHUB_REPO_PLACEHOLDER``; nothing can be published to it."""

RELEASE_DOWNLOAD_URL_TEMPLATE = "https://github.com/{repo}/releases/latest/download/{file}"
"""Plain redirect to a release asset: no GitHub API, no token, no rate limit."""

# --- Paths -----------------------------------------------------------------------------------

PIPELINE_ROOT = Path(__file__).resolve().parent.parent
"""The ``pipeline/`` directory."""

REPO_ROOT = PIPELINE_ROOT.parent

CACHE_DIR = PIPELINE_ROOT / ".cache"
"""Cross-run cache root; safe to delete, gitignored, cached by the CI workflow."""

IMAGE_CACHE_DIR = CACHE_DIR / "images"
"""Downloaded card visuals: ``<lang>/<card_id>.webp``."""

EMBED_CACHE_DB = CACHE_DIR / "embeddings.sqlite"
"""Embedding cache keyed by ``(card_id, lang, image_sha256, model_sha256)``."""

MODELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models"
"""Model assets shipped in the APK; the pipeline runs the very same files."""

MODELS_LOCK = REPO_ROOT / "tools" / "models.lock"
"""``<asset-name> <sha256> <bytes> <url>`` lock file checked before embedding."""

DEFAULT_OUT_DIR = REPO_ROOT / "dist"
"""Default output directory for ``pack-<lang>.db.gz`` and ``manifest.json``."""
