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

FALLBACK_IMAGE_LANGS: tuple[str, ...] = ("en", "de", "it", "es", "pt")
"""TCGdex languages ``--en-image-fallback`` borrows a visual from for a card that has none in its
own language (same card id, same artwork, other text). Ordered: the first language with an image
wins, and the card's own language is skipped."""

USER_AGENT = "tcg-scan-app pack pipeline (+https://tcgdex.dev)"

# --- Rate limiting and retries (FR-21, docs/pack-format.md §4.6-§4.7) -------------------------

MIN_REQUEST_INTERVAL_S = 0.150
"""Minimum delay between two TCGdex requests."""

MAX_RETRIES = 15
"""Attempts per request before raising ``TcgdexUnavailable``."""

RETRY_BACKOFF_BASE_S = 1.0
"""First backoff delay; attempt *n* waits ``BASE * 2 ** (n - 1)``, capped by the value below."""

RETRY_BACKOFF_MAX_S = 30.0

REQUEST_TIMEOUT_S = 30.0
"""Per-request socket/read timeout."""

# --- Limitless TCG, optional extra source of JA visuals (docs/pack-format.md §4.9) ----------------

LIMITLESS_PAGE_URL_TEMPLATE = "https://limitlesstcg.com/cards/jp/{code}"
"""Set page of Limitless TCG; one request per set lists every card and its image URL."""

LIMITLESS_SET_CODE_ALIASES: dict[str, str] = {"SV-P": "SVP", "M-P": "MP"}
"""TCGdex JA set id -> Limitless set code, for the sets whose code differs. Any other set id is
used as is."""

LIMITLESS_USER_AGENT = "tcg-scan-app pack pipeline (open-source card catalogue builder, low rate)"

LIMITLESS_MIN_REQUEST_INTERVAL_S = 1.0
"""Minimum delay between two Limitless requests (page or image): a volunteer-run site."""

LIMITLESS_MAX_RETRIES = 4
"""Attempts per Limitless request. Fewer than TCGdex's: the source is optional, so a Limitless
outage must cost seconds, not minutes, of a build."""

LIMITLESS_MAX_RUN_SECONDS = 9000.0
"""Wall-clock budget of one Limitless source, counted from its creation (the ja pack build in
``build``). Once spent, the source switches itself off: the cards left stay without a visual.
150 min keeps a full build (about 1 h without Limitless) well under the 360 min CI job timeout."""

# --- pokemontcg.io, optional extra source of FR visuals (docs/pack-format.md §4.10) -----------------

PTCGIO_CARDS_URL_TEMPLATE = (
    "https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/cards/en/{set_id}.json"
)
"""Per-set card list of the open pokemon-tcg-data repository: every card with its exact image URL.
One request per set; the pokemontcg.io API itself is not used (it answered HTTP 500 when tested)."""

PTCGIO_SET_ALIASES: dict[str, str] = {
    "swsh4.5sv": "swsh45sv",
    "sm3.5": "sm35",
    "sm7.5": "sm75",
    "swsh12.5gg": "swsh12pt5gg",
    "smp": "smp",
    "swshp": "swshp",
    "svp": "svp",
    "sve": "sve",
    "swsh9tg": "swsh9tg",
    "swsh10tg": "swsh10tg",
    "swsh11tg": "swsh11tg",
    "swsh12tg": "swsh12tg",
    "cel25": "cel25",
    "cel25cc": "cel25c",
    "ecard2": "ecard2",
    "exu": "ex10",
    "hgssp": "hsp",
    "tk-ex-latia": "tk1a",
    "tk-ex-latio": "tk1b",
    "tk-ex-p": "tk2a",
    "tk-ex-m": "tk2b",
    "2011bw": "mcd11",
    "2012bw": "mcd12",
    "2014xy": "mcd14",
    "2015xy": "mcd15",
    "2016xy": "mcd16",
    "2017sm": "mcd17",
    "2021swsh": "mcd21",
    "2022swsh": "mcd22",
}
"""TCGdex FR set id -> pokemontcg.io set id. Only sets whose card numbering is the pokemontcg.io one
(checked on the card names and Pokédex numbers of a sample): the FR McDonald's 2018/2019 sets have
40 cards where pokemontcg.io has 12, so they are absent, and so is every set pokemontcg.io does not
serve (the Mega Evolution sets and the 30th Celebration are hosted elsewhere). Only the cards the
data lists get a visual."""

PTCGIO_NUMBER_OVERRIDES: dict[str, dict[str, str]] = {
    "cel25cc": {
        "CC001": "2_A",
        "CC002": "4_A",
        "CC003": "15_A1",
        "CC004": "73_A",
        "CC005": "8_A",
        "CC006": "15_A2",
        "CC007": "15_A3",
        "CC008": "24_A",
        "CC009": "20_A",
        "CC010": "66_A",
        "CC011": "9_A",
        "CC012": "86_A",
        "CC013": "88_A",
        "CC014": "93_A",
        "CC015": "17_A",
        "CC016": "15_A4",
        "CC017": "109_A",
        "CC018": "145_A",
        "CC019": "107_A",
        "CC020": "113_A",
        "CC021": "114_A",
        "CC022": "54_A",
        "CC023": "97_A",
        "CC024": "76_A",
        "CC025": "60_A",
    }
}
"""TCGdex FR set id -> {localId -> pokemontcg.io card id suffix} for the sets that are not numbered
alike (Celebrations Classic Collection: TCGdex ``CC001``.. against the cards' original numbers).
Any other card is matched on its ``localId`` with the digit padding ignored."""

PTCGIO_USER_AGENT = "tcg-scan-app pack pipeline (open-source card catalogue builder, low rate)"

PTCGIO_MIN_REQUEST_INTERVAL_S = 0.25
"""Minimum delay between two pokemontcg.io requests (data file or image): a CDN, so shorter than
Limitless's."""

PTCGIO_MAX_RETRIES = 4
"""Attempts per pokemontcg.io request; the source is optional, so an outage must cost seconds, not
minutes, of a build."""

PTCGIO_MAX_RUN_SECONDS = 9000.0
"""Wall-clock budget of one pokemontcg.io source, counted from its creation (the fr pack build in
``build``), not from its own requests. The fr build also downloads TCGdex and Poképédia images in
that window (~45 min), so a 30 min cap switched the source off before the late sets; 150 min, like
Limitless and Poképédia, stays under the 360 min job timeout. Once spent, the source switches itself
off and the cards left stay without a visual."""

# --- Poképédia, optional extra source of FR own-print visuals (docs/pack-format.md §4.11) ----------

POKEPEDIA_API_URL = "https://www.pokepedia.fr/api.php"
"""MediaWiki API of Poképédia; the card-file listing is read through it, ~60 requests per run."""

POKEPEDIA_EXTENSION_OVERRIDES: dict[str, str] = {
    # Series prefix or wording that differs from the TCGdex name.
    "dp4": "Diamant & Perle Duels au sommet",
    "hgss4": "HS Triomphe",
    "pop7": "POP Series 7",
    # Promos: the TCGdex set is named after the promo series, the wiki after its code.
    "basep": "Promo Wizards",
    "mep": "Promo ME",
    "svp": "Promo SV",
    # Basic Energy cards.
    "mee": "Méga-Évolution Énergie de base",
    "sve": "Écarlate et Violet Énergie de base",
    # McDonald's: the FR 2018/2019 collections are the « (France) » ones; 2022/2023 are « Combat
    # Express ».
    "2018sm-fr": "Collection McDonald's 2018 (France)",
    "2019sm-fr": "Collection McDonald's 2019 (France)",
    "2022swsh": "Combat Express (2022)",
    "2023sv": "Combat Express (2023)",
    # Subsets printed in the main set's numbering (TG01.., GG01.., SV001..).
    "swsh9tg": "Épée et Bouclier Stars Étincelantes",
    "swsh10tg": "Épée et Bouclier Astres Radieux",
    "swsh11tg": "Épée et Bouclier Origine Perdue",
    "swsh12tg": "Épée et Bouclier Tempête Argentée",
    "swsh12.5gg": "Zénith Suprême",
    "swsh4.5sv": "Destinées Radieuses",
    "sma": "Destinées Occultes",
    "exu": "EX Forces Cachées",
    # Trainer kits: the wiki names them « Kit du Dresseur <Pokémon> » under the series prefix.
    "tk-bw-e": "Noir & Blanc Kit du Dresseur Minotaupe",
    "tk-bw-z": "Noir & Blanc Kit du Dresseur Zoroark",
    "tk-dp-l": "Diamant & Perle Kit Dresseur Lucario",
    "tk-dp-m": "Diamant & Perle Kit Dresseur Manaphy",
    "tk-ex-m": "EX Kit Dresseur 2 Négapi",
    "tk-ex-p": "EX Kit Dresseur 2 Posipi",
    "tk-sm-l": "Soleil et Lune Kit du Dresseur Lougaroc",
    "tk-sm-r": "Soleil et Lune Kit du Dresseur Raichu d'Alola",
    "tk-xy-p": "XY Kit du Dresseur Pikachu Catcheur",
}
"""TCGdex FR set id -> Poképédia extension name (the ``<Extension>`` of ``Carte_<Extension>_<n>.png``),
for the sets the automatic name match does not find. Any other set is matched on its name, accents,
case and punctuation ignored, exactly or as the unique extension ending with it (the wiki prefixes
the series: « Noir & Blanc Nobles Victoires »). Each entry was checked by comparing the Pokémon of
the wiki article with the TCGdex card name. Sets with no extension on the wiki (Celebrations
Classic Collection, 30th Anniversary Classic Collection, the alternate-art cards) are absent: they keep the other sources."""

POKEPEDIA_USER_AGENT = "tcg-scan-app pack pipeline (open-source card catalogue builder, low rate)"

POKEPEDIA_MIN_REQUEST_INTERVAL_S = 1.0
"""Minimum delay between two Poképédia requests (listing page or image): a volunteer-run wiki."""

POKEPEDIA_MAX_RETRIES = 4
"""Attempts per Poképédia request; the source is optional, so an outage must cost seconds, not
minutes, of a build."""

POKEPEDIA_MAX_RUN_SECONDS = 9000.0
"""Wall-clock budget of one Poképédia source, counted from its creation (the fr pack build in
``build``). Once spent, the source switches itself off: the cards left stay on the other sources and
the files already cached are reused by the next run. 150 min covers the ~3 000 candidate cards at
one request per second and keeps a full build well under the 360 min CI job timeout."""

POKEPEDIA_THUMB_WIDTH = 400
"""Width in pixels of the MediaWiki thumbnail downloaded instead of the ~750 kB, 736 px original
(the model squashes to 224 px anyway); the original is the fallback when the thumbnail fails."""

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
"""Downloaded card visuals: ``<lang>/<card_id>.webp``, plus ``ja-limitless/<card_id>.png``,
``en-ptcgio/<card_id>.png`` and ``fr-pokepedia/<card_id>.png`` for the optional Limitless,
pokemontcg.io and Poképédia sources."""

EMBED_CACHE_DB = CACHE_DIR / "embeddings.sqlite"
"""Embedding cache keyed by ``(card_id, lang, image_sha256, model_sha256)``."""

MODELS_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "models"
"""Model assets shipped in the APK; the pipeline runs the very same files."""

MODELS_LOCK = REPO_ROOT / "tools" / "models.lock"
"""``<asset-name> <sha256> <bytes> <url>`` lock file checked before embedding."""

DEFAULT_OUT_DIR = REPO_ROOT / "dist"
"""Default output directory for ``pack-<lang>.db.gz`` and ``manifest.json``."""
