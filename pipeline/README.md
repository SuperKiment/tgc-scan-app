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
| `python3 -m tcgscan_pipeline build [--langs fr,ja] [--out dist] [--max-sets N] [--en-image-fallback] [--jp-extra-images] [--fr-extra-images] [--fr-pokepedia-images] [--previous-manifest FILE]` | full run: crawl → images → embed → pack → manifest → guard |

The build is **all-or-nothing** (FR-22): either every requested pack *and* `manifest.json` land in
the output directory, or it is left empty and the process exits non-zero, so the previously
published release stays in place.

Packs and manifest are first written to a private staging directory next to `--out`, and moved
into it, manifest last, only once the 2 % guard (`guard.py`) opens. The guard compares with the
manifest of the latest release of `TCGSCAN_REPO`, or with the local file given by
`--previous-manifest` (dry runs of the guard). It closes when a language loses more than 2 % of its
cards, when a language of the previous manifest is not rebuilt (so `--langs fr` alone is refused
once a `ja` pack is published), or when TCGdex stayed unavailable. `--out` is emptied of earlier
`pack-*.db.gz` and `manifest.json` at the start of every run.

`--max-sets N` limits the crawl to the first N sets per language — the cheap way to dry-run the
whole chain without pulling ~20 000 cards.

## Modules

| module | role |
|---|---|
| `config.py` | every constant shared with the app and the docs: API base, languages, excluded series, image suffix, embedding model id/sha256, schema versions, the 2 % threshold, cache paths |
| `tcgdex.py` | rate-limited JSON client: 150 ms minimum between requests, 5 attempts with exponential backoff, non-JSON body treated as retryable, `TcgdexUnavailable` on final failure |
| `catalog.py` | series/set/card crawl, `tcgp` exclusion, canonical `name_norm` |
| `images.py` | `{image}/low.webp` download into the image cache |
| `limitless.py` | optional (`--jp-extra-images`) Limitless TCG source of `ja` visuals for cards TCGdex has no image for: one set page per set, 1 s between requests, never raises, vectors tagged `has_embed = 1` |
| `pokepedia.py` | optional (`--fr-pokepedia-images`) Poképédia source of `fr` visuals: scans of the French print, tried right after TCGdex's own image; one MediaWiki listing of every card file per run, 1 s between requests, never raises, vectors tagged `has_embed = 1` |
| `ptcgio.py` | optional (`--fr-extra-images`) pokemontcg.io source of `fr` visuals for cards no TCGdex language has an image for: one `pokemon-tcg-data` card list per set, 0.25 s between requests, never raises, vectors tagged `has_embed = 2` |
| `embed.py`, `embed_cache.py` | DINOv2-small int8 embedding and its cross-run SQLite cache |
| `packbuild.py` | the gzipped SQLite pack |
| `manifest.py` | writes `manifest.json` (validated with the app's own rules), reads the previously published one (404 tolerated) |
| `guard.py` | the release gate: 2 % card loss, missing pack, TCGdex unavailable |
| `cli.py`, `__main__.py` | the `build` command: staging, gate, all-or-nothing publication into `--out` |

## Rate limiting and caches

TCGdex is a free community API: the client keeps at least **150 ms** between two requests and makes
**no per-card call** (a set response already carries its full card list), so a full language costs
roughly 1 + 20 + 203 requests.

Two caches under `pipeline/.cache/` (gitignored, restored by the CI workflow) keep repeat runs
cheap (FR-21):

| path | content | key |
|---|---|---|
| `.cache/images/<lang>/<card_id>.webp` | downloaded card visuals | file presence |
| `.cache/images/ja-limitless/<card_id>.png` | Limitless visuals (`--jp-extra-images`) | file presence |
| `.cache/images/en-ptcgio/<card_id>.png` | pokemontcg.io visuals (`--fr-extra-images`) | file presence |
| `.cache/images/fr-pokepedia/<card_id>.png` | Poképédia visuals (`--fr-pokepedia-images`) | file presence |
| `.cache/embeddings.sqlite` | int8 embedding blobs | `(card_id, lang, image_sha256, model_sha256)` |

`--jp-extra-images` (off by default, on in the CI workflow) adds the Limitless TCG source for `ja`
cards without a TCGdex image: a failure there (404 set page, network, parse) only leaves the card
without a visual and never fails the build. See `../docs/pack-format.md` §4.9.

`--fr-extra-images` (off by default, on in the CI workflow) adds the pokemontcg.io source for `fr`
cards still without a visual after `--en-image-fallback`: only the sets of
`config.PTCGIO_SET_ALIASES`, tagged `has_embed = 2`, with the same never-fails behaviour. See
`../docs/pack-format.md` §4.10.

`--fr-pokepedia-images` (off by default, on in the CI workflow) adds the Poképédia source for `fr`
cards TCGdex has no own image for, tried before the other-language fallback and pokemontcg.io, and
tagged `has_embed = 1` (the image is the French print). TCGdex sets map to Poképédia extensions by
name, with `config.POKEPEDIA_EXTENSION_OVERRIDES` for the ones whose name differs. A first run
downloads ~3 000 images at 1 s each (about 50 min, capped at 150 min); the cache makes the next
runs free, with the same never-fails behaviour. See `../docs/pack-format.md` §4.11.

Deleting any of them only costs time. No image byte ever enters a pack.

## Environment

| variable | meaning |
|---|---|
| `TCGSCAN_REPO` | `owner/repo` used to fetch the previously published `manifest.json` for the 2 % guard; defaults to the placeholder of `AppConfig.GITHUB_REPO`; while it is the placeholder, no manifest is fetched and the guard is not applied (a warning says so) |
