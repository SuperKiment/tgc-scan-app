# Manual debug checklist

This project ships no automated tests (decision recorded in `docs/plans/tcg-scan-v1.md`). The acceptance
criteria that need a device, a network or a physical lot of cards are verified by hand with this
checklist. `SPEC.md` (repository root) defines the AC-n and EC-n identifiers used below. Measured
accuracy and latency go into [benchmark.md](benchmark.md).

The UI is in French; button names below are the labels shown on screen.

## 0. Before anything else: check `AppConfig.GITHUB_REPO`

`app/src/main/java/org/tcgscan/app/AppConfig.kt` sets `GITHUB_REPO = "SuperKiment/tgc-scan-app"`. A fork
must change it to its own `owner/repo` and rebuild.

Download-based criteria (AC-11 to AC-17) also need at least one `packs-<date>` release marked latest on
that repository. If none exists, create it first: GitHub > Actions > `packs` > Run workflow (AC-5 in
section 3). Without a release, the manifest download fails and only « Continuer sans pack » works in
onboarding.

## 1. Build and install the APK

```bash
./gradlew assembleDebug                      # debug keystore, debuggable (needed for run-as below)
bash tools/check-foss.sh                     # AC-2, AC-3, D5: no gms/mlkit/firebase, 3 permissions, no ORT telemetry
./gradlew assembleRelease                    # AC-1 (falls back to the debug keystore without TCGSCAN_* variables)

adb devices                                  # one device, authorised
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
# 32-bit phones: app-armeabi-v7a-debug.apk; release APKs are in app/build/outputs/apk/release/
```

There is no universal APK and no `applicationIdSuffix`: the package is always `org.tcgscan.app`.
Install an APK that matches the device ABI (`adb shell getprop ro.product.cpu.abi`), otherwise the app
stops at start with the OpenCV load error.

Start from a clean state when a step says « fresh install »:

```bash
adb shell pm clear org.tcgscan.app           # wipes database, preferences, packs and scan photos
```

## 2. Build a pack locally and push it to the device

The pipeline runs from `pipeline/`, inside a Python 3.12 virtual environment. A system Python without
the dependencies fails with `ModuleNotFoundError: No module named 'cv2'`. On Python 3.14, pip builds
numpy 2.2.6 from source, so use 3.12: `uv` downloads it on its own (`.venv/` is gitignored).

```bash
bash tools/fetch_models.sh                     # from the repo root: the embedding model must be present (see docs/models.md)
cd pipeline
uv venv -p 3.12 .venv                          # once
uv pip install -p .venv -r requirements.txt    # once (numpy 2.2.6, opencv-python-headless 4.12, onnxruntime 1.31.1, …)
.venv/bin/python -m tcgscan_pipeline build --langs fr --max-sets 3 --out ../dist   # drop --max-sets for the full catalogue
cd ..
```

Expected log: `TCGSCAN_REPO is not set: … the 2 % guard is not applied` (normal locally). With
`--max-sets 3` you get `fr: 3 sets, 192 cards, 166 vectors …` (26 cards of `basep` have no visual, so
they are OCR-only), `release gate open` and `emitted into …/dist: manifest.json, pack-fr.db.gz` (~76 KB).

The result is `dist/pack-fr.db.gz` and `dist/manifest.json`. The app reads the decompressed SQLite file
at `/data/data/org.tcgscan.app/files/packs/fr/pack.db`. `adb push` cannot write there directly: stage the
file in `/data/local/tmp` and copy it with `run-as` (debug build only).

```bash
gunzip -k dist/pack-fr.db.gz                              # keeps the .gz, produces dist/pack-fr.db
adb push dist/pack-fr.db /data/local/tmp/pack.db
adb shell run-as org.tcgscan.app mkdir -p files/packs/fr
adb shell run-as org.tcgscan.app cp /data/local/tmp/pack.db files/packs/fr/pack.db
adb shell rm /data/local/tmp/pack.db
adb shell run-as org.tcgscan.app ls -l files/packs/fr     # pack.db present, owned by the app
```

Force-stop and reopen the app after the copy. Language code `ja` follows the same path with
`--langs ja` and `files/packs/ja/pack.db`.

> **Hand-pushed pack.** The catalogue, the review screen, the scan screen and the collection screen
> all use the packs whose file is on disk, so a pushed pack enables browsing, manual search, card
> detail, scanning and the collection. Scan and collection derive a record from the pack's `pack_meta`
> when `installed_packs` has none. Installing from Settings > « Packs de langues » > « Installer »
> remains the normal path (it is the only one that records the version for update checks).

Useful device-side inspection (debug build):

```bash
adb logcat -s ScanViewModel:* ScanPipeline:* PackCatalog:* OnnxEnv:* MatchPackCache:*
# Pull the database (WAL included) for a sqlite3 session on the computer:
for f in tcgscan.db tcgscan.db-wal tcgscan.db-shm; do
  adb exec-out run-as org.tcgscan.app cat databases/$f > /tmp/$f 2>/dev/null
done
sqlite3 /tmp/tcgscan.db "SELECT lang, card_id, physical_lang FROM owned_cards;"
```

## 3. Acceptance criteria, in order

AC-1 to AC-4 are covered by section 1 plus a look at the README and « À propos » (FR-29 notices). The
rest, in the order to run them:

| AC | What to do | Pass when |
|---|---|---|
| AC-5 | GitHub, Actions, `packs`, « Run workflow ». Download the release assets, then `sha256sum pack-fr.db.gz pack-ja.db.gz` and compare with `packs[].sha256` in `manifest.json`. | Both hashes match the manifest. |
| AC-6 | `gunzip -k` the pack, then `sqlite3 pack-fr.db "SELECT count(*) FROM sets WHERE id LIKE 'tcgp%' OR series_id='tcgp'; SELECT count(*) FROM cards;"`. Compare the card count with the TCGdex count at build time (the pipeline prints `<lang>: <n> cards` at the end of a build). Check the `.gz` contains no image (`gzip -l`, size in the MB range). | 0 `tcgp` sets, counts equal, no image data. |
| AC-7 | Cannot be forced by hand against the real API. Read the build log of a workflow run: on a TCGdex outage or a loss above 2 %, the run must fail at « Build packs and manifest » and no `packs-<date>` release appears. | No release created on a failed guard. |
| AC-8, AC-9, AC-10 | Physical lot of 200 FR and 100 JP cards, fresh install of both packs, scan each card once. Fill [benchmark.md](benchmark.md). | At least 190/200 FR first; at least 80 % of the JP cards with a reference first; P90 below 2 s; at most 2 % wrongly pre-ticked. |
| AC-11 | Install FR (Settings). Switch to airplane mode. Scan tab: scan 20 cards in one burst, « Terminer », then « Tout valider ». Open the Collection tab. | The burst ends, the review validates, the 20 cards appear, with no network. |
| AC-12 | Scan an English card whose French equivalent has the same id. In the review it must not be ticked and shows « Carte anglaise ». Tick it manually, validate. Check with `sqlite3` (section 2). | `owned_cards` holds the FR `card_id` with `physical_lang = en`. |
| AC-13 | Scan a card already in the collection. | The review lists it under « Déjà possédées »; after validation the collection is unchanged. |
| AC-14 | Scan 3 cards, then `adb shell am force-stop org.tcgscan.app` mid-burst. Reopen the app, Scan tab. | « Une session de scan inachevée a été retrouvée » with « Reprendre » showing the 3 cards. |
| AC-15 | Catalogue: add a card to « Liste de souhaits » and to a custom list (Lists tab). Scan it or open its sheet and « Marquer comme possédée ». | It disappears from the wishlist and stays in the custom list. |
| AC-16 | Settings > « Sauvegarde et restauration »: export JSON to a folder. `adb uninstall org.tcgscan.app`, reinstall, reinstall the pack, import the JSON with « Remplacer ». | Ownerships, wishlist and lists are identical to before. |
| AC-17 | Export JSON (A). Settings > « Mettre à jour » on a pack with a newer release (or delete and reinstall it). Export JSON again (B). `diff <(jq -S .ownerships A.json) <(jq -S .ownerships B.json)` (and the same on `.lists`). | Content identical, only `exportedAt` differs. |
| AC-18 | Own a card, then replace the pack by one built with `--max-sets` that no longer has it (or edit its `cards` row in a copy). Open Collection and export. | The card shows as « carte inconnue » with its raw id and is in the export. |
| AC-19 | EC-1 to EC-5, below. | Each expected behaviour is observed. |

### Edge cases covered by AC-19 (EC-1 to EC-5)

| EC | Gesture | Expected |
|---|---|---|
| EC-1 | Fresh install, airplane mode, first launch. | Onboarding explains the need for internet with « Réessayer »; « Continuer sans pack » opens the app; Scan tab says « Aucun pack installé ». |
| EC-2 | Settings > install a pack, switch airplane mode on mid-download, then off, « Réessayer ». Then corrupt the `.gz` on the host serving it or use a release whose hash is wrong. | The download resumes where it stopped; a wrong SHA-256 deletes the download, shows « Empreinte SHA-256 incorrecte » and keeps the previous pack. |
| EC-3 | Fill the device storage (large file pushed to `/sdcard`) until less than the pack size is free, then install. | The required size is shown before download; nothing is installed. |
| EC-4 | Settings > Apps > TCG Scan > Permissions: deny the camera, open the Scan tab. | Explanation with « Ouvrir les réglages »; Catalogue, Collection and Lists still work. |
| EC-5 | Put two cards in the frame. | « Présentez une seule carte à la fois » and no capture. |

Other edge cases worth a look during the scan runs: EC-7 (low light: « Faible lumière : activez la
lampe », row in « À vérifier »), EC-13 and EC-14 (leave the review without validating: the session is
kept, nothing is added), EC-15 (airplane mode: card visuals fall back to the scan photo, then a text card),
a small translate icon next to a card name in the review means the visual match came from another language's reference image (`has_embed = 2`; same card, language to double-check; not the « Carte anglaise » reason),
EC-17 (pack with a newer `pack_schema`: refused, old pack kept), EC-18 (corrupt import file: refused, no
change), EC-20 (removing a pack with owned cards: warning, ownerships kept).

### On-device learning (schema 2)

When a detection is validated in the review (pre-ticked or picked by hand, search included), the
embedding of the scan is kept as an extra visual reference of the chosen card, under its pack language
(an English card filed under its French id is learned under `fr`). At most the 3 newest vectors are kept
per card. The matcher searches them next to the pack vectors, so a card with `has_embed` 0 or 2 can be
pre-ticked on a later scan once it was validated once (same thresholds, and the OCR must still confirm
the name or the number). They are not exported in the JSON backup, and « Remplacer » leaves them alone.

Check: scan a card without own visual reference (typically a Japanese one), validate it, scan it again in
a new burst: it should now land in « Reconnues » instead of « À vérifier ». Then, with the database pulled
as in section 2:

```bash
sqlite3 /tmp/tcgscan.db "SELECT count(*) FROM learned_embeddings; SELECT lang, card_id, length(vector) FROM learned_embeddings;"
```

`length(vector)` must be 384 and a card never has more than 3 rows. Settings > « Données » > « Oublier les
cartes apprises » (confirmation dialog) empties the table; the row shows the live count. An upgrade from a
schema 1 install keeps the collection (migration 1 to 2, no data loss).

## 4. Reporting

Record a failing step with the build commit (`git rev-parse --short HEAD`), the APK ABI, the device and the
relevant `adb logcat` excerpt. Benchmark figures belong in [benchmark.md](benchmark.md), not here.
