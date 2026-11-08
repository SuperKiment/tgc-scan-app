# Pack pipeline

Builds the TCG Scan language packs: crawls the [TCGdex](https://tcgdex.dev) API, embeds the card
visuals with the very same ONNX model the app ships, and writes `pack-<lang>.db.gz` plus
`manifest.json` for a GitHub Release.

The artefact format, the embedding contract and the release guard are specified in
**[`../docs/pack-format.md`](../docs/pack-format.md)** — that document is authoritative, this
package only implements it.

## Install

Python **3.12+** (CI uses 3.12; on 3.14, numpy 2.2.6 has no wheel and pip compiles it from source).

```bash
python3 -m pip install -r pipeline/requirements.txt
```

The embedding stage additionally needs the model assets, which are not committed:

```bash
bash tools/fetch_models.sh
```

## Run

All commands below are run from `pipeline/` — not from the repository root — since that is where
the `tcgscan_pipeline` package is importable, e.g.:

```bash
cd pipeline
python3 -m tcgscan_pipeline build --langs fr,ja --out ../dist
```

| command | what it does |
|---|---|
| `python3 -m tcgscan_pipeline.tcgdex --probe fr` | smoke-probes the API and prints the series count of a language |
| `python3 -m tcgscan_pipeline build [--langs fr,ja] [--out dist] [--max-sets N] [--en-image-fallback]` | full run: crawl → images → embed → pack → manifest → guard |

The build is **all-or-nothing** (FR-22): either every requested pack *and* `manifest.json` land in
the output directory, or it is left empty and the process exits non-zero, so the previously
published release stays in place.

`--max-sets N` limits the crawl to the first N sets per language — the cheap way to dry-run the
whole chain without pulling ~20 000 cards.

## Modules

| module | role |
|---|---|
| `config.py` | every constant shared with the app and the docs: API base, languages, excluded series, image suffix, embedding model id/sha256, schema versions, the 2 % threshold, cache paths |
| `tcgdex.py` | rate-limited JSON client: 150 ms minimum between requests, 5 attempts with exponential backoff, non-JSON body treated as retryable, `TcgdexUnavailable` on final failure |
| `catalog.py` | series/set/card crawl, `tcgp` exclusion, canonical `name_norm` |
| `images.py` | `{image}/low.webp` download into the image cache |
| `embed.py`, `embed_cache.py` | DINOv2-small int8 embedding and its cross-run SQLite cache |
| `packbuild.py` | the gzipped SQLite pack |
| `manifest.py`, `guard.py`, `cli.py` | `manifest.json`, the release gate and the entry point |

## Rate limiting and caches

TCGdex is a free community API: the client keeps at least **150 ms** between two requests and makes
**no per-card call** (a set response already carries its full card list), so a full language costs
roughly 1 + 20 + 203 requests.

Two caches under `pipeline/.cache/` (gitignored, restored by the CI workflow) keep repeat runs
cheap (FR-21):

| path | content | key |
|---|---|---|
| `.cache/images/<lang>/<card_id>.webp` | downloaded card visuals | file presence |
| `.cache/embeddings.sqlite` | int8 embedding blobs | `(card_id, lang, image_sha256, model_sha256)` |

Deleting either only costs time. No image byte ever enters a pack.

## Environment

| variable | meaning |
|---|---|
| `TCGSCAN_REPO` | `owner/repo` used to fetch the previously published `manifest.json` for the 2 % guard; defaults to the placeholder of `AppConfig.GITHUB_REPO` |
