# pipeline

> Repository `tgc-scan-app`, path `tgc-scan-app/pipeline/`. Checked on 2026-11-12 from `pipeline/README.md`, `pipeline/requirements.txt`, every module of `pipeline/tcgscan_pipeline/` (working tree, including the uncommitted `embed.py`, `embed_cache.py`, `packbuild.py`), `tools/models.lock`, `docs/pack-format.md`.

## What it is for

Python pipeline that builds the language packs the Android app installs. It crawls the TCGdex API, downloads each card's low-resolution visual, embeds it with the same ONNX model as the app, and writes one gzipped SQLite pack per language (`pack-<lang>.db.gz`). Images are only used to compute embeddings; no image byte enters a pack (`pipeline/tcgscan_pipeline/packbuild.py`, module docstring).

## Tech

- Type: pipeline of command-line modules, run with `python3 -m`. No build file other than `pipeline/requirements.txt`.
- Python: 3.12+ according to `pipeline/README.md` ("Install"). No version check in the code. Python 3.14.4 is the interpreter present on the dev machine.
- Pinned dependencies (`pipeline/requirements.txt`): `requests==2.32.*`, `numpy==2.2.6`, `onnxruntime==1.31.1`, `opencv-python-headless==4.12.0.88`.
- Install: `python3 -m pip install -r pipeline/requirements.txt`, then `bash tools/fetch_models.sh` for the model.
- Run (from `pipeline/`, so that the package is importable). Each module has its own `_main`:

| Command | Purpose |
|---|---|
| `python3 -m tcgscan_pipeline.tcgdex --probe <lang>` | Probe the API, print the series count |
| `python3 -m tcgscan_pipeline.catalog --lang <l> [--max-sets N]` | Crawl and print card rows |
| `python3 -m tcgscan_pipeline.images --lang <l> --set <id> [--max-cards N]` | Cache one set's visuals |
| `python3 -m tcgscan_pipeline.embed --image <path>` or `--lang <l> --set <id> [--max-cards N] [--en-image-fallback]` | Embed one file or one set |
| `python3 -m tcgscan_pipeline.packbuild --lang <l> [--out DIR] [--max-sets N] [--en-image-fallback] [--keep-db]` | Build one pack (default out `dist/` at the repository root) |

- Dry run: `--max-sets N` on `catalog` and `packbuild` (example in the `packbuild.py` docstring: `--max-sets 2 --out /tmp/packtest`).

### Modules (`pipeline/tcgscan_pipeline/`)

| Module | Role |
|---|---|
| `config.py` | Constants shared with the app: API base, languages `fr`/`ja`, excluded series `tcgp`, image suffix, model asset and sha256, schema versions, 2 % threshold, cache and output paths |
| `tcgdex.py` | Rate-limited TCGdex JSON client (150 ms between requests, 5 attempts, backoff), plus `--probe` |
| `catalog.py` | Series/set/card crawl, series exclusion, `name_norm` |
| `images.py` | Download of `{image}/low.webp` into the image cache; https and host `tcgdex.net` allow-list, no redirects |
| `embed.py` | Loads the ONNX model, checks its sha256 against `tools/models.lock`, preprocesses to 224x224, outputs 384 int8 components |
| `embed_cache.py` | SQLite embedding cache keyed by `(card_id, lang, image_sha256, model_sha256)` |
| `packbuild.py` | Builds the SQLite pack (DDL of `docs/pack-format.md` section 2), `VACUUM`, gzip, prints path, size and sha256 |

## Dependencies

- External services: TCGdex API `https://api.tcgdex.net/v2` (`pipeline/tcgscan_pipeline/config.py`, `API_BASE`) and the TCGdex image host (`tcgdex.net` or a subdomain, `images.py`). Nothing else called in the code read.
- Model: `embed_dinov2s_int8.onnx` (sha256 `c179f8f7...`), read from `app/src/main/assets/models/` (`config.py`, `MODELS_DIR`). That is the file shipped in the APK. It is fetched by `tools/fetch_models.sh` from the URL pinned in `tools/models.lock` (Hugging Face `onnx-community/dinov2-small`). The pipeline refuses to run if the hash differs from the lock.
- Embedding contract shared with the app: 224x224 `cv2.resize` with `INTER_LINEAR`, 384 int8 components, `round(v * 127)` (`embed.py`, `config.py`). The app loads the same asset in `app/src/main/java/org/tcgscan/app/scan/Embedder.kt` and `ModelAssets.kt` (files located, not read); the contract is stated as shared in `docs/pack-format.md` (preprocessing parity not verified in the Kotlin code).
- Caches (gitignored, safe to delete): `pipeline/.cache/images/<lang>/<card_id>.webp` and `pipeline/.cache/embeddings.sqlite`.
- Outputs: `pack-<lang>.db.gz` in `--out` (default `dist/`). Consumed by the app (`docs/pack-format.md` lists `data/pack/PackInstaller.kt` as the reader).
- Environment variable: `TCGSCAN_REPO` is only described in `pipeline/README.md` (guard); no code reading it was found in the working tree.

## Place in the whole

Offline producer of the data the Android app (`app/`) downloads as language packs. The app does the scan with the same model; the pipeline precomputes the reference embeddings.

> [!WARNING]
> **Gap between README and working tree.** `pipeline/README.md` and `docs/pack-format.md` describe `manifest.py`, `guard.py`, `cli.py`, a `python3 -m tcgscan_pipeline build` command and `.github/workflows/packs.yml` (publishing to GitHub Releases). None of these exist in the working tree: no `__main__.py`, no `.github/` folder. The manifest, the 2 % guard and the Release publication are therefore documented but not implemented here yet (as of this check). `packbuild.py` only builds one language at a time.

## Tests

Nothing found: no `tests` folder under `pipeline/` or at the repository root, and no test dependency in `pipeline/requirements.txt`. This is a project decision (verification is `python3 -m py_compile pipeline/tcgscan_pipeline/*.py` plus a dry run).

## Related docs

- [Pack format and embedding contract](../../../pack-format.md) (authoritative).
- [Models](../../../models.md).
- `pipeline/README.md` (in the repository, not a doc of this folder).
