# PLAN: TCG Scan v1 — Android scanner + pack pipeline
spec: /home/kiment/Projects/GitHub/tgc-scan-app/SPEC.md   date: 2026-11-08
status legend: todo | doing | done | failed-review

Scope note from the operator: **no tests of any kind** (no unit, no instrumented, no CI test job, no
test dependencies). AC-6, AC-7, AC-8, AC-9, AC-10, AC-19 and every AC needing a physical lot are
out of scope *as verification*, but the behaviours they describe (tcgp exclusion, `no available
server` retry, 2 % loss guard, no-partial-publish, pre-check precision gating) must still be
implemented. Exit criteria are therefore build/compile based:
`./gradlew assembleDebug` for Android, `python3 -m py_compile` (+ cheap dry run) for the pipeline.
No `git init`, no commits.

---

## Decisions (taken autonomously; all verified against the machine or the network today)

**D1 Toolchain.** Gradle **8.14** (distribution already unpacked at
`~/.gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/bin/gradle`, so the
wrapper is generated with that binary — no network, no `gradle` on PATH needed).
AGP **8.7.3**, Kotlin **2.1.0**, KSP **2.1.0-1.0.29**, compose-compiler plugin 2.1.0.
`compileSdk = 35`, `targetSdk = 35` (only `android-35` is installed in `~/Android/Sdk/platforms`),
`minSdk = 26`. No `jvmToolchain {}` block (only JDK 21 is installed): Java/Kotlin target **11**,
run on JDK 21. `ANDROID_HOME` is unset → `local.properties` must carry
`sdk.dir=/home/kiment/Android/Sdk` (file is gitignored but required locally; CI sets `ANDROID_HOME`).

**D2 Module layout.** One Gradle module `:app` (no feature modules — keeps every batch compilable),
plus a non-Gradle `pipeline/` Python package and `tools/` scripts.

**D3 Dependency pins** — chosen because they are already in `~/.gradle/caches/modules-2` (fast,
offline-tolerant) and are the last versions that do not demand compileSdk 36:
Compose BOM `2024.12.01`, material3 `1.3.1`, activity-compose `1.9.3`, navigation-compose `2.8.5`,
lifecycle `2.8.7`, core-ktx `1.15.0`, room `2.6.1` (KSP), datastore-preferences `1.1.1`,
work-runtime-ktx `2.10.0`, kotlinx-serialization-json `1.7.3`, kotlinx-coroutines `1.9.0`.
Fetched fresh (verified HTTP 200 today): CameraX `1.4.2`, OkHttp `4.12.0`, Coil `2.7.0`
(Coil 2.x, not Coil3, to avoid Kotlin-metadata risk), ONNX Runtime Android `1.31.1`,
OpenCV `org.opencv:opencv:4.12.0`.

**D4 OpenCV is a dependency** (`org.opencv:opencv:4.12.0`, Apache-2.0, minSdk 21, zero Google
components; arm64 lib 23 MB, armeabi-v7a 15 MB). Rationale: card-quad detection, perspective warp
**and** the PP-OCR DB detector post-processing (`findContours` + `minAreaRect` on the probability
map) are otherwise ~400 LOC of hand-written CV. The pipeline pins `opencv-python-headless==4.12.0.88`
(verified installable on Python 3.14 via its `cp37-abi3` wheel, reports `cv2 4.12.0`) so resize
kernels are bit-comparable between device, local pipeline and CI.

**D5 ONNX Runtime telemetry must be stripped.** Verified: `onnxruntime-android-1.31.1.aar` declares
`<provider android:name="ai.onnxruntime.TelemetryInitializer">` plus `INTERNET` and
`ACCESS_NETWORK_STATE`. The permissions are exactly what AC-3 allows; the provider violates NFR-5
and is removed in the app manifest with `tools:node="remove"`.

**D6 Embedding model (device + CI, same file).**
`https://huggingface.co/onnx-community/dinov2-small/resolve/main/onnx/model_quantized.onnx`
— 24 446 700 B, sha256 `c179f8f7f592449c4c1bca4cd124a7538021428c5ffb89afde9503935b197efb`,
int8 ONNX export of `facebook/dinov2-small` (**Apache-2.0**). Verified by running it:
input `pixel_values (1,3,224,224) float32` → output `last_hidden_state (1,257,384)`; 47 ms/img on
this x86 box with 2 threads. **Embedding = L2-normalised CLS token** (`last_hidden_state[0,0,:]`,
384 dims). The *same file* is used by the CI pipeline, so on-device and pack vectors are comparable.
Preprocessing fixed on both sides: warped card → `cv2.resize(…,(224,224), INTER_LINEAR)` (aspect
squashed, no letterbox), RGB, `/255`, mean `(0.485,0.456,0.406)`, std `(0.229,0.224,0.225)`, NCHW.
Fallback if the repo disappears: `Xenova/dinov2-small` `onnx/model_quantized.onnx` (same export).

**D7 OCR models (all Apache-2.0, all verified by running them).** Shipped in APK assets:
| asset | source URL | bytes | sha256 |
|---|---|---|---|
| `ocr_det.onnx` | `https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx` | 4 745 517 | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` |
| `ocr_rec_latin.onnx` | `https://huggingface.co/tobiichioriguchi/latin_PP-OCRv3_mobile_rec_onnx/resolve/main/inference.onnx` | 8 993 434 | `2bdd80b2a52fb2bb08b7407867eeb04fc8059b828dd46e500a9d48ba76ace9d8` |
| `ocr_rec_japan.onnx` | `https://huggingface.co/tobiichioriguchi/japan_PP-OCRv3_mobile_rec_onnx/resolve/main/inference.onnx` | 10 089 078 | `2e397c27461751befc82eca78c9b19e87322ebee34e7316468b21856f9977d64` |
| `dict_latin.txt` | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/dict/latin_dict.txt` | 468 (185 lines) | `8e6d4e3629788c35c31f7e530287d6147b549bb7a265bd6708bb281134429e2c` |
| `dict_japan.txt` | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/dict/japan_dict.txt` | 17 332 (4399 lines) | `1dcfcb41eec90576a945b3084f22ade11ced506e24f14879245b071698f308e8` |
Verified shapes: det `x (1,3,H,W)` → sigmoid prob map `(1,1,H,W)` (62 ms at 640² on x86);
latin rec `x (N,3,48,W)` → `(N,T,187)` = blank + 185 dict chars + space (matches `latin_dict.txt`);
japan rec → `(N,T,4401)` = blank + 4399 + space (matches `japan_dict.txt`). The **latin** (not
`en_PP-OCRv3`) recogniser is used because the `en_dict.txt` of 95 chars has no French accents.
Mirrors if a repo vanishes: `itextresearch/itext-latin_PP-OCRv3_mobile_rec_infer`,
`itextresearch/itext-japan_PP-OCRv3_mobile_rec_infer` (same `inference.onnx`, Apache-2.0),
`cycloneboy/japan_PP-OCRv4_rec_infer`. If a fetch fails the build must **fail loudly**; recognition
is never stubbed.

**D8 Model shipping.** All five model assets (~48 MB) go in `app/src/main/assets/models/`, not
downloaded with packs: scan then works offline right after install and model/code versions can
never drift. Expected ABI-split APK ≈ 80-95 MB (ORT 34 MB + OpenCV 23 MB + models 48 MB for arm64,
compressed). Recorded in `docs/models.md` with licences (FR-29/NFR-1).

**D9 Pack format.** One pack per language = **gzipped SQLite**, `pack-<lang>.db.gz`, installed as
`<filesDir>/packs/<lang>/pack.db`. Tables: `pack_meta(key,value)` (`pack_schema=1`, `lang`,
`built_at`, `card_count`, `embed_model`, `embed_dim=384`), `sets(id,name,series_id,card_count_total,
card_count_official,release_date,symbol_url,logo_url)`, `cards(card_id,local_id,name,name_norm,
set_id,image_url,has_embed)`, `embeddings(card_id, vec BLOB)` with `vec` = 384 **int8** values
(L2-normalised float × 127, so cosine = int dot / (127²)), `ocr_tokens(card_id, token)` for
number/set-code search. Indices on `cards(name_norm)`, `cards(local_id)`, `cards(set_id)`,
`ocr_tokens(token)`. **No FTS5** (not guaranteed on Android 8 SQLite) — normalised-column LIKE
queries instead. ~17 k FR cards × 384 B ≈ 6.6 MB of vectors → **brute-force int8 cosine over a
direct ByteBuffer, no ANN index** (≈6.6 M MACs, a few ms).

**D10 Language codes.** Verified: TCGdex uses `fr` and **`ja`** (`/v2/jp/sets` → 404, `/v2/ja/sets`
→ 200). Pack ids are `fr` and `ja`; the French UI labels them « Français » and « Japonais ».

**D11 Catalogue crawl.** Verified today: `GET /v2/{lang}/series` lists series,
`GET /v2/{lang}/series/{id}` lists its sets, and `GET /v2/{lang}/sets/{id}` returns the set with its
full card list (`id`, `localId`, `name`, `image`) plus `cardCount`. So the pipeline needs ~1 + ~20 +
~203 requests per language and **no per-card call**. `tcgp` is excluded by series id (FR-20).
Images: `{image}/low.webp` (verified, 16 KB for `swsh3-136`); `high.webp` and `low.png` also exist.

**D12 Printed set code.** TCGdex exposes no printed extension code. The OCR confirmation therefore
matches on `localId` + `cardCount.total` (the printed « 136/201 »), on the set id as a token, and on
the set name; documented in `docs/pack-format.md`. No invented codes.

**D13 Distribution URLs.** Manifest and packs are read from
`https://github.com/<REPO>/releases/latest/download/manifest.json` (plain redirect, no GitHub API,
no token, no rate limit). `<REPO>` is the single constant `AppConfig.GITHUB_REPO` with placeholder
value **`"CHANGE-ME-OWNER/tcg-scan-app"`** → *the user must set this once the repo exists* (also
flagged in README and in the About screen).

**D14 Signing.** Release signing reads `TCGSCAN_KEYSTORE_FILE`, `TCGSCAN_KEYSTORE_PASSWORD`,
`TCGSCAN_KEY_ALIAS`, `TCGSCAN_KEY_PASSWORD` from env/`~/.gradle/gradle.properties`; when absent the
release build falls back to the debug keystore with a warning so `assembleRelease` never breaks.
The CI release job decodes `secrets.TCGSCAN_KEYSTORE_B64` into a temp file. **No key is generated or
stored by this plan.**

**D15 FR-4** (compute a FR card's embedding from the EN image of the same id) is `should` and the
SPEC conditions it on a physical lot. Implemented as pipeline flag `--en-image-fallback`,
**default off**, and the resulting rows are marked `has_embed=2` so the app can down-weight them.

**D16 Confidence policy** (FR-8/FR-9, AC-10 behaviour): a detection is pre-checked only if
cosine ≥ 0.72 **and** margin to the runner-up ≥ 0.06 **and** the OCR name or number agrees;
everything else goes to « à vérifier » with the top 3 candidates. Cards with no visual reference
(`has_embed=0`) are *never* pre-checked (FR-3). Constants live in one file, `scan/ScanTuning.kt`.

**D17 Images in the UI.** Coil 2.7.0 with an explicit 256 MB bounded disk cache (FR-25) on the
`{image}/low.webp` URL; fallback chain = cached visual → user scan photo → text-only card (FR-26).

**D18 No tests, per operator instruction.** `docs/benchmark.md` is created as a stub with the
reference-device table to fill during the manual session.

---

## Tasks

### T1: Bootstrap the Gradle build  [done]  (lite: no)
deps: -
files: gradle/wrapper/gradle-wrapper.properties, gradle/wrapper/gradle-wrapper.jar, gradlew, gradlew.bat, settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, local.properties, .gitignore
do: Generate the wrapper by running the already-unpacked distribution:
  `~/.gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/bin/gradle wrapper --gradle-version 8.14 --distribution-type bin` in the repo root.
  Write `settings.gradle.kts` (pluginManagement + dependencyResolutionManagement with `google()`,
  `mavenCentral()`, rootProject.name = "tcg-scan-app", `include(":app")`), a root `build.gradle.kts`
  declaring the AGP/Kotlin/KSP/compose plugins `apply false`, `gradle.properties`
  (`android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx2g`, `kotlin.code.style=official`),
  `local.properties` with `sdk.dir=/home/kiment/Android/Sdk`, and `.gitignore` (build/, .gradle/,
  local.properties, .idea/, pipeline cache dirs, `*.keystore`).
  Fill `gradle/libs.versions.toml` with every pin of D1/D3 (including opencv, onnxruntime, camerax,
  okhttp, coil, serialization).
exit: `./gradlew -q projects` prints `:app` (with the `app/` dir still empty this may be run after T2;
  run `./gradlew --version` here and it must report Gradle 8.14 / JVM 21).

### T2: App module skeleton, manifest, theme, navigation stubs  [done]  (lite: no)
deps: T1
files: app/build.gradle.kts, app/proguard-rules.pro, app/src/main/AndroidManifest.xml, app/src/main/java/org/tcgscan/app/TcgScanApp.kt, app/src/main/java/org/tcgscan/app/MainActivity.kt, app/src/main/java/org/tcgscan/app/AppConfig.kt, app/src/main/java/org/tcgscan/app/ui/Routes.kt, app/src/main/java/org/tcgscan/app/ui/TcgScanNavHost.kt, app/src/main/java/org/tcgscan/app/ui/theme/Theme.kt, app/src/main/res/values/strings.xml, app/src/main/res/values/themes.xml
do: `com.android.application` + kotlin + ksp + compose + serialization plugins. namespace/applicationId
  `org.tcgscan.app`, minSdk 26, compile/targetSdk 35, Java/Kotlin 11, `buildFeatures { compose = true }`,
  `splits { abi { isEnable = true; reset(); include("arm64-v8a","armeabi-v7a"); isUniversalApk = false } }`,
  signing per D14, `packaging { resources.excludes += listOf("META-INF/{AL2.0,LGPL2.1}") }`.
  Declare all deps of D3/D4 (ORT, OpenCV, CameraX, Room+KSP, Coil, OkHttp, DataStore, Work, Compose BOM).
  Manifest: only `CAMERA`, `INTERNET`, `ACCESS_NETWORK_STATE`; `android:screenOrientation="portrait"`;
  `<provider android:name="ai.onnxruntime.TelemetryInitializer" android:authorities="${applicationId}.onnxruntime_telemetry_initializer" tools:node="remove" />` (D5).
  `AppConfig.kt` holds `GITHUB_REPO = "CHANGE-ME-OWNER/tcg-scan-app"`, `MANIFEST_URL`, `PACK_SCHEMA = 1`,
  `DB_SCHEMA = 1`, `IMAGE_CACHE_BYTES = 256L*1024*1024`.
  `Routes.kt` + `TcgScanNavHost.kt` are written **final now**, with bottom-bar routes scan/collection/
  catalog/lists/settings and these exact composable signatures that later tasks fill in place:
  `ScanRoute(nav)`, `ReviewRoute(nav, sessionId: Long)`, `CollectionRoute(nav)`, `CatalogRoute(nav)`,
  `SetBrowseRoute(nav, lang: String, setId: String)`, `CardDetailRoute(nav, lang: String, cardId: String)`,
  `ListsRoute(nav)`, `ListDetailRoute(nav, listId: Long)`, `SettingsRoute(nav)`, `AboutRoute(nav)`,
  `OnboardingRoute(nav)`, `BackupRoute(nav)`. Create each as a one-line placeholder in its own file
  under `ui/<area>/` so no later task edits the NavHost. French strings externalised in strings.xml.
exit: `./gradlew assembleDebug` succeeds and produces `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
  and `app-armeabi-v7a-debug.apk`.

### T3: Licence, README, format and model documentation  [done]  (lite: no)
deps: -
files: LICENSE, README.md, docs/pack-format.md, docs/models.md, docs/benchmark.md
do: `LICENSE` = verbatim GPL-3.0 text. `README.md` = goal, build instructions (JDK 21, `local.properties`
  `sdk.dir`, `./gradlew assembleRelease`), the FR-29 mentions (fan project unaffiliated with Nintendo /
  The Pokémon Company / Creatures; data by TCGdex; dependency + model licences), the list of CI secrets
  of D14, and a prominent "set `AppConfig.GITHUB_REPO`" note (D13).
  `docs/pack-format.md` is the **authoritative** spec: D9 schema verbatim (DDL included), D6 embedding
  contract (CLS token, 224×224 INTER_LINEAR, ImageNet norm, int8×127 encoding), D11 crawl rules,
  D12 set-code matching, manifest.json JSON shape, 2 % guard rule.
  `docs/models.md` = D7/D6/D8 tables with URLs, bytes, sha256, licences, I/O shapes.
  `docs/benchmark.md` = stub table (reference device, FR/JP accuracy, P90) to fill manually.
exit: `grep -c "GNU GENERAL PUBLIC LICENSE" LICENSE` ≥ 1; docs/pack-format.md contains the full `CREATE TABLE`
  statements for `pack_meta`, `sets`, `cards`, `embeddings`, `ocr_tokens`.

### T4: Room database: entities, DAOs, migrations  [done]  (lite: no)
deps: T2
files: app/src/main/java/org/tcgscan/app/data/db/AppDatabase.kt, app/src/main/java/org/tcgscan/app/data/db/Entities.kt, app/src/main/java/org/tcgscan/app/data/db/OwnershipDao.kt, app/src/main/java/org/tcgscan/app/data/db/CardListDao.kt, app/src/main/java/org/tcgscan/app/data/db/ScanSessionDao.kt, app/src/main/java/org/tcgscan/app/data/db/DbProvider.kt
do: Entities: `OwnedCard(lang, cardId, physicalLang, addedAt)` PK(lang,cardId);
  `CardListEntity(id, name, isSystem, createdAt)`; `CardListItem(listId, lang, cardId)` PK(3);
  `ScanSessionEntity(id, startedAt, defaultLang, status)`;
  `ScanDetectionEntity(id, sessionId, photoPath, state, detectedLang, chosenLang, chosenCardId,
  candidatesJson, score, createdAt)`. `AppDatabase` version 1, `exportSchema = true` into
  `app/schemas`, `@TypeConverters` only if needed. `DbProvider` = singleton builder with an
  `onCreate` callback inserting the system wishlist row (id 1, `isSystem = true`, name from strings)
  and a `MIGRATIONS` array (empty, with a comment explaining NFR-9 policy).
  DAOs expose Flow queries: ownership by lang/set, `isOwned`, insert/delete, list CRUD, list
  membership, add/remove, session + detection CRUD, unfinished-session lookup (EC-13/EC-14).
exit: `./gradlew assembleDebug` succeeds (KSP generates `AppDatabase_Impl`) and `app/schemas/org.tcgscan.app.data.db.AppDatabase/1.json` exists.

### T5: HTTP layer and manifest service  [doing]  (lite: no)
deps: T2
files: app/src/main/java/org/tcgscan/app/data/net/Http.kt, app/src/main/java/org/tcgscan/app/data/net/ManifestService.kt, app/src/main/java/org/tcgscan/app/data/net/PackManifest.kt, app/src/main/java/org/tcgscan/app/data/net/RangeDownloader.kt
do: `Http` builds one shared OkHttpClient (no cookie jar, 15 s timeouts, follow redirects, a
  `retryOnNonJson` interceptor-free helper). `PackManifest` = `@Serializable` models matching
  docs/pack-format.md (`schema`, `version`, `generatedAt`, `packs: Map<String, PackEntry>` with
  `file`, `bytes`, `sha256`, `cardCount`, `packSchema`, `installedBytes`).
  `ManifestService.fetch()` GETs `AppConfig.MANIFEST_URL`, and on a non-JSON body (the measured
  `no available server` case) or 5xx retries 3× with 1/2/4 s backoff, then returns a typed failure.
  `RangeDownloader.download(url, target, expectedBytes, onProgress)` resumes with
  `Range: bytes=<existing>-`, writes to `<target>.part`, handles 200-vs-206, and returns the file
  (EC-2); it exposes `freeSpaceOk(required)` for EC-3.
exit: `./gradlew assembleDebug` succeeds; `ManifestService` has no reference to any GitHub API host (only `github.com/.../releases/latest/download`).

### T6: Fetch and lock the model assets  [done]  (lite: no)
deps: T2, T3
files: tools/fetch_models.sh, tools/models.lock, app/src/main/assets/models/.gitkeep
do: `tools/models.lock` lists the 5 assets of D7/D6 as `<asset-name> <sha256> <bytes> <url>` (values
  already measured — copy them verbatim from docs/models.md). `tools/fetch_models.sh` (bash, `set -euo
  pipefail`, `curl -fL`) downloads each entry into `app/src/main/assets/models/`, skips files whose
  sha256 already matches, **fails loudly** on mismatch or 404 and prints the mirror list of D7.
  Run it once so the assets exist locally. Do not alter the files afterwards.
exit: `bash tools/fetch_models.sh` exits 0 and `sha256sum -c` over tools/models.lock passes for
  `embed_dinov2s_int8.onnx`, `ocr_det.onnx`, `ocr_rec_latin.onnx`, `ocr_rec_japan.onnx`, `dict_latin.txt`,
  `dict_japan.txt`; `./gradlew assembleDebug` still succeeds.

### T7: Pack reader — catalogue queries and embedding index  [done]  (lite: no)
deps: T2, T3
files: app/src/main/java/org/tcgscan/app/data/pack/PackPaths.kt, app/src/main/java/org/tcgscan/app/data/pack/PackCatalog.kt, app/src/main/java/org/tcgscan/app/data/pack/PackModels.kt, app/src/main/java/org/tcgscan/app/data/pack/EmbeddingIndex.kt, app/src/main/java/org/tcgscan/app/data/pack/InstalledPacksStore.kt
do: `PackPaths` resolves `<filesDir>/packs/<lang>/pack.db` and the staging dir.
  `PackCatalog` opens the pack with `SQLiteDatabase.openDatabase(..., OPEN_READONLY)` (not Room — the
  pack is produced externally and versioned by `pack_meta.pack_schema`), and exposes: `meta()`,
  `sets()`, `cardsInSet(setId)`, `searchByName(query, limit)` on `name_norm`,
  `searchByNumber(localId)`, `card(cardId)`, `cardsByIds(ids)`, `tokensFor(cardId)`.
  `EmbeddingIndex.load(lang)` streams `embeddings` into one direct `ByteBuffer` plus a parallel
  `cardId` array, and offers `topK(query: FloatArray, k): List<Pair<String,Float>>` doing int8 dot
  products (cosine = dot / 127f / 127f) — brute force per D9, no ANN.
  `InstalledPacksStore` (DataStore Preferences) records per-lang installed version, card count, bytes
  and `packSchema`, with a Flow.
exit: `./gradlew assembleDebug` succeeds; `EmbeddingIndex.topK` is the only similarity entry point and
  allocates no per-call arrays beyond the result list.

### T8: Pack install, update and removal  [todo]  (lite: no)
deps: T5, T7
files: app/src/main/java/org/tcgscan/app/data/pack/PackInstaller.kt, app/src/main/java/org/tcgscan/app/data/pack/PackRepository.kt, app/src/main/java/org/tcgscan/app/data/pack/PackInstallState.kt
do: `PackInstaller.install(lang, entry, onProgress)`: free-space check for `bytes + installedBytes`
  (EC-3) → `RangeDownloader` → SHA-256 verify (mismatch ⇒ delete `.part`, typed error, old pack kept,
  EC-2) → GZIP-decompress into `packs/<lang>/pack.db.tmp` → open it read-only and refuse if
  `pack_meta.pack_schema > AppConfig.PACK_SCHEMA` (EC-17) → `renameTo` the final path (atomic, never
  a partial install) → update `InstalledPacksStore`. `remove(lang)` deletes the dir and warns the
  caller when owned cards of that language exist (EC-20) — it never touches the Room DB.
  `PackRepository` exposes `installed: Flow<Map<String,InstalledPack>>`, `checkForUpdates()` using
  `ManifestService` (offline ⇒ typed `Offline`, EC-1), and `updateAvailable(lang)`.
  `PackInstallState` = sealed progress/result type for the UI.
exit: `./gradlew assembleDebug` succeeds; no code path writes `packs/<lang>/pack.db` other than the
  `renameTo` in `PackInstaller`.

### T9: ONNX runtime wiring and the image embedder  [todo]  (lite: no)
deps: T3, T6
files: app/src/main/java/org/tcgscan/app/scan/OnnxEnv.kt, app/src/main/java/org/tcgscan/app/scan/ModelAssets.kt, app/src/main/java/org/tcgscan/app/scan/Embedder.kt, app/src/main/java/org/tcgscan/app/scan/Preprocess.kt
do: `ModelAssets` copies the 5 assets to `<filesDir>/models/` once (sha-less, size+name guard) because
  ORT needs a file path for large models; `OnnxEnv` owns the single `OrtEnvironment`, builds sessions
  with `intraOpNumThreads = min(4, cores-1)`, `optimizationLevel = ALL_OPT`, and closes them.
  `Preprocess` (uses OpenCV `Imgproc.resize` + `INTER_LINEAR`) turns a `Mat` card crop into the exact
  tensor of D6 and is the only place that knows the normalisation constants.
  `Embedder.embed(cardMat): FloatArray` runs `pixel_values` → takes `last_hidden_state[0,0,:]` (CLS)
  → L2-normalises → returns 384 floats. Also `embedQuantized()` returning the int8 form for debug.
  Call `OpenCVLoader.initLocal()` in `TcgScanApp` (edit that file) and fail loudly if it returns false.
exit: `./gradlew assembleDebug` succeeds; `Embedder` reads only `embed_dinov2s_int8.onnx` and returns a
  384-length array, and `Preprocess` contains the constants 0.485/0.456/0.406 and 0.229/0.224/0.225.

### T10: On-device OCR (PP-OCR detection + recognition)  [todo]  (lite: no)
deps: T9
files: app/src/main/java/org/tcgscan/app/scan/ocr/TextDetector.kt, app/src/main/java/org/tcgscan/app/scan/ocr/TextRecognizer.kt, app/src/main/java/org/tcgscan/app/scan/ocr/CtcDecoder.kt, app/src/main/java/org/tcgscan/app/scan/ocr/OcrEngine.kt, app/src/main/java/org/tcgscan/app/scan/ocr/OcrModels.kt
do: `TextDetector`: resize the warped card so both sides are multiples of 32 (max side 640), normalise
  with PP-OCR det mean/std (0.485/0.456/0.406, 0.229/0.224/0.225), run `ocr_det.onnx`, then DB
  post-process with OpenCV: threshold 0.3 → `findContours` → `minAreaRect` → unclip ratio 1.6 →
  sort boxes top-to-bottom; return rotated quads in card coordinates.
  `TextRecognizer`: crop+deskew each quad, resize to height 48 keeping ratio (width padded to a
  multiple of 32, capped 320), normalise to [-1,1], batch up to 8, run the latin **or** japan model
  by requested script, `CtcDecoder` does greedy blank-collapsing decode over the 187/4401 class axis
  using `dict_latin.txt` / `dict_japan.txt` (index 0 = blank, last class = space) and returns text +
  mean class probability.
  `OcrEngine.read(cardMat, script): OcrResult` = boxes + texts + confidences, and runs **both** scripts
  when the script is unknown, keeping the higher mean confidence.
exit: `./gradlew assembleDebug` succeeds; the decoder's dictionary load asserts
  `dict.size + 2 == outputClasses` for both models.

### T11: Card detection, stabilisation and perspective correction  [todo]  (lite: no)
deps: T2
files: app/src/main/java/org/tcgscan/app/scan/detect/CardQuadDetector.kt, app/src/main/java/org/tcgscan/app/scan/detect/Stabilizer.kt, app/src/main/java/org/tcgscan/app/scan/detect/Warper.kt, app/src/main/java/org/tcgscan/app/scan/detect/FrameConverter.kt
do: `FrameConverter` turns a CameraX `ImageProxy` (YUV_420_888) into an OpenCV `Mat` (grey for
  detection, RGB for the final crop) honouring `rotationDegrees`.
  `CardQuadDetector.detect(grey)`: downscale to max side 480, `GaussianBlur` → `Canny` →
  `findContours(RETR_EXTERNAL)` → keep contours with area ≥ 8 % of the frame → `approxPolyDP` to 4
  points, convex, aspect ratio in 1.25..1.85 → order corners. Returns `None`, `Single(quad)`, or
  `Multiple` when ≥ 2 qualifying quads (EC-5), plus a blur score (Laplacian variance) and a mean
  luminance for the low-light hint (EC-7).
  `Stabilizer` requires N = 3 consecutive frames whose corner positions move < 2 % of the frame
  diagonal before emitting a capture, then arms a cooldown until the quad leaves the frame (FR-7).
  `Warper.warp(rgb, quad, w, h)` = `getPerspectiveTransform` + `warpPerspective` to 480×672 (card
  aspect) and a 224×224 variant for the embedder.
exit: `./gradlew assembleDebug` succeeds; `CardQuadDetector` returns the `Multiple` case and exposes
  blur/luma, and `Stabilizer` has the cooldown that blocks a second capture of an unmoved card.

### T12: OCR text interpretation (name, number, set code, language)  [todo]  (lite: no)
deps: T2, T3
files: app/src/main/java/org/tcgscan/app/scan/text/TextNormalizer.kt, app/src/main/java/org/tcgscan/app/scan/text/CardTextParser.kt, app/src/main/java/org/tcgscan/app/scan/text/ScriptDetector.kt
do: `TextNormalizer.normalize(s)` = NFD, strip combining marks, lowercase, collapse non-alphanumerics —
  **the exact same rule the pipeline writes into `cards.name_norm`** (docs/pack-format.md).
  `CardTextParser.parse(ocr): ParsedCardText` extracts: candidate names (longest tokens from the top
  third of the card), the `localId`/`total` pair via regexes `(\d{1,3})\s*/\s*(\d{1,3})` and bare
  `\d{1,3}` in the bottom strip, a promo/alphanumeric localId form (`SV\d+`, `XY\d+`, `H\d+`), and
  set-id-like tokens.
  `ScriptDetector.detect(ocr)` returns FR / EN / JA / UNKNOWN: kana+CJK ratio ≥ 0.15 ⇒ JA; markers
  `PV`/`Dégâts`/`Faiblesse` ⇒ FR; `HP`/`Weakness`/`Resistance` ⇒ EN; otherwise UNKNOWN so the session
  default applies and the card goes to « à vérifier » (FR-5).
exit: `./gradlew assembleDebug` succeeds; `TextNormalizer` is referenced by the matcher and documented
  in docs/pack-format.md as the canonical normalisation.

### T13: Candidate matcher and confidence policy  [todo]  (lite: no)
deps: T7, T12
files: app/src/main/java/org/tcgscan/app/scan/match/Matcher.kt, app/src/main/java/org/tcgscan/app/scan/match/Candidate.kt, app/src/main/java/org/tcgscan/app/scan/ScanTuning.kt
do: `ScanTuning` holds every threshold of D16 as named constants (visual accept, margin, OCR agreement
  bonus, name-distance max, top-K = 30, candidates kept = 3).
  `Matcher.match(embedding, parsedText, script, sessionLang, installedPacks)`:
  visual top-K from `EmbeddingIndex` of the chosen language → re-score each candidate by combining
  cosine with OCR agreement (exact `localId` match, `localId`/`total` match, normalised-name
  Levenshtein ratio) → sort → build `Candidate` list of 3 with a `MatchOutcome` of
  `Confident` / `Review` / `NoCandidate`. OCR-only path when the pack has no embedding for that card
  or no quad embedding is available (FR-3): name+number lookup through `PackCatalog`, always `Review`,
  pre-selected (not pre-checked) when the match is exact and unique.
  Language rules: detected EN ⇒ `Review` with the FR card of the same id proposed and
  `physicalLang = "en"` carried on the candidate (FR-11), or the « anglais non pris en charge »
  reason when the FR id is absent (EC-10); detected language whose pack is missing ⇒ `Review` with
  reason `PACK_MISSING` (EC-12). `Confident` requires `hasEmbed == 1` (never for `has_embed` 0 or 2).
exit: `./gradlew assembleDebug` succeeds; `Matcher` never returns `Confident` when `hasEmbed != 1` or when
  the runner-up margin is below `ScanTuning.MIN_MARGIN`.

### T14: Scan pipeline, session persistence and photo store  [todo]  (lite: no)
deps: T4, T9, T10, T11, T13
files: app/src/main/java/org/tcgscan/app/scan/ScanPipeline.kt, app/src/main/java/org/tcgscan/app/scan/DedupTracker.kt, app/src/main/java/org/tcgscan/app/scan/ScanPhotoStore.kt, app/src/main/java/org/tcgscan/app/scan/ScanSessionRepository.kt
do: `ScanPipeline.onFrame(imageProxy, sessionLang)` = convert → detect → stabilise → warp → embed →
  OCR → parse → match, all on a single-thread dispatcher, returning a `ScanEvent`
  (`Idle`, `MultipleCards`, `TooBlurry`, `LowLight`, `Detected(detectionId)`).
  `DedupTracker` drops a capture whose embedding cosine to an already-captured one in the session is
  ≥ 0.97 and whose parsed number matches, marking it `DUPLICATE` instead of adding it (FR-7/EC-9).
  `ScanPhotoStore` writes the warped RGB crop as JPEG quality 80 into
  `<filesDir>/scan/<sessionId>/<detectionId>.jpg` (app-private, never exported) and deletes by
  detection or by session (FR-26).
  `ScanSessionRepository` creates/reopens a session, persists each detection immediately with its
  candidates as JSON (EC-13), exposes `activeSession()` and `discard(sessionId)` which also deletes
  the photos.
exit: `./gradlew assembleDebug` succeeds; every detection is written to Room before `ScanEvent.Detected`
  is emitted, and no collection write happens anywhere in this task's files (FR-9).

### T15: Scan screen (CameraX, counter, torch, permission)  [todo]  (lite: no)
deps: T14
files: app/src/main/java/org/tcgscan/app/ui/scan/ScanRoute.kt, app/src/main/java/org/tcgscan/app/ui/scan/ScanViewModel.kt, app/src/main/java/org/tcgscan/app/ui/scan/CameraPreview.kt, app/src/main/res/values/strings.xml
do: `CameraPreview` binds a CameraX `Preview` + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`,
  720p target) to the lifecycle and forwards frames to `ScanViewModel`. The screen shows a card guide
  frame, the live detected-card counter, a torch toggle, the transient hints (« une seule carte »,
  « activez la lampe »), a haptic + flash feedback on each detection (FR-6), and « Terminer » →
  `ReviewRoute(sessionId)`. Camera-permission rationale with a link to app settings when denied, while
  catalogue/collection stay reachable (EC-4). A banner offers to resume an unfinished session at
  entry (EC-13) and blocks scanning with an install-pack call to action when no pack is installed (EC-1).
exit: `./gradlew assembleDebug` succeeds; ScanRoute resolves `ReviewRoute` through the NavHost routes
  created in T2 without editing `TcgScanNavHost.kt`.

### T16: Review screen and collection commit  [todo]  (lite: no)
deps: T4, T14
files: app/src/main/java/org/tcgscan/app/ui/review/ReviewRoute.kt, app/src/main/java/org/tcgscan/app/ui/review/ReviewViewModel.kt, app/src/main/java/org/tcgscan/app/ui/review/ReviewRows.kt, app/src/main/java/org/tcgscan/app/data/CollectionRepository.kt
do: Three sections: « Reconnues » (pre-checked), « À vérifier » (unchecked, each row showing the scan
  photo, the 3 candidates, a manual-search sheet over `PackCatalog`, a « rejeter » action and the
  reason label), « Déjà possédées » (read-only, no write — FR-10/EC-9).
  `CollectionRepository.commit(sessionId, decisions)` runs one transaction: insert `OwnedCard`
  (`physicalLang` from the decision, default = card lang), delete the card from the wishlist
  (FR-17/AC-15) while leaving other lists untouched, mark the session `COMMITTED`, delete the photos of
  rejected and committed-but-unneeded detections. Leaving the screen without validating keeps the
  session and writes nothing (EC-14). A single « Tout valider » action commits the pre-checked set (FR-9).
exit: `./gradlew assembleDebug` succeeds; `CollectionRepository.commit` is the only writer of `OwnedCard`
  from the scan flow and runs inside a single `withTransaction`.

### T17: Catalogue — search, set browsing, card detail  [todo]  (lite: no)
deps: T4, T7, T23
files: app/src/main/java/org/tcgscan/app/ui/catalog/CatalogRoute.kt, app/src/main/java/org/tcgscan/app/ui/catalog/CatalogViewModel.kt, app/src/main/java/org/tcgscan/app/ui/catalog/SetBrowseRoute.kt, app/src/main/java/org/tcgscan/app/ui/catalog/CardDetailRoute.kt, app/src/main/java/org/tcgscan/app/ui/catalog/CardDetailViewModel.kt
do: Catalogue root: language tabs over the installed packs, a search field querying `name_norm` LIKE and
  `local_id` (debounced 250 ms, paged by 50), and a set list grouped by series. `SetBrowseRoute` shows a
  grid of the set's cards with an « possédée » badge from the Room ownership Flow and an inline
  toggle (FR-14). `CardDetailRoute` shows visual, name, localId/total, set, language, ownership toggle,
  list memberships with add/remove, « ajouter à la wishlist », and the « carte inconnue » rendering when
  the id is owned but absent from the pack (EC-16/EC-18).
exit: `./gradlew assembleDebug` succeeds; ownership badges come from a Room Flow and card data from
  `PackCatalog` (no duplication of card metadata in Room).

### T18: Collection screen  [todo]  (lite: no)
deps: T4, T7, T23
files: app/src/main/java/org/tcgscan/app/ui/collection/CollectionRoute.kt, app/src/main/java/org/tcgscan/app/ui/collection/CollectionViewModel.kt
do: Lists owned cards joined against `PackCatalog`, filterable by language and by set, searchable by
  name or number, with a swipe/menu « retirer » action and a count header (FR-13). Owned ids missing
  from the pack appear as « carte inconnue » with their raw id (EC-16). Empty state points to the scan
  screen.
exit: `./gradlew assembleDebug` succeeds; removing an ownership updates the list without a manual refresh
  (Flow-driven) and unknown ids are rendered rather than dropped.

### T19: Wishlist and custom lists  [todo]  (lite: no)
deps: T4, T7, T23
files: app/src/main/java/org/tcgscan/app/ui/lists/ListsRoute.kt, app/src/main/java/org/tcgscan/app/ui/lists/ListsViewModel.kt, app/src/main/java/org/tcgscan/app/ui/lists/ListDetailRoute.kt, app/src/main/java/org/tcgscan/app/data/CardListRepository.kt
do: `ListsRoute` shows the wishlist first (system, no delete, no rename) then user lists with create /
  rename / delete (deleting a list never touches ownership — FR-18). `ListDetailRoute` shows the list's
  cards resolved through `PackCatalog`, with remove-from-list and an ownership badge. A card may belong
  to several lists. `CardListRepository` centralises the CRUD and guards `isSystem` against
  deletion/rename.
exit: `./gradlew assembleDebug` succeeds; `CardListRepository.delete` throws/returns an error for the
  system list and never deletes `OwnedCard` rows.

### T20: Settings, pack management, About  [todo]  (lite: no)
deps: T3, T8
files: app/src/main/java/org/tcgscan/app/ui/settings/SettingsRoute.kt, app/src/main/java/org/tcgscan/app/ui/settings/SettingsViewModel.kt, app/src/main/java/org/tcgscan/app/ui/settings/PackRow.kt, app/src/main/java/org/tcgscan/app/ui/about/AboutRoute.kt
do: Settings: default scan language, installed packs with version/card count/size, « mettre à jour »
  when the manifest offers a newer version (size shown before download, FR-23), « installer » for a
  missing language, « supprimer » with the EC-20 warning, download progress / resume / SHA-256 error
  surfaces from `PackInstallState`, a « vider le cache d'images » action, and entries to Backup and
  About. About screen carries the FR-29 mentions, the GPL-3.0 notice, the dependency and model licence
  list (read from `docs/models.md` content duplicated into strings) and the configured
  `AppConfig.GITHUB_REPO` with a warning when it is still the placeholder.
exit: `./gradlew assembleDebug` succeeds; the About screen text contains « non affilié », « TCGdex » and
  « GPL-3.0 », and pack size is displayed before any download starts.

### T21: First-run onboarding  [todo]  (lite: no)
deps: T8, T20
files: app/src/main/java/org/tcgscan/app/ui/onboarding/OnboardingRoute.kt, app/src/main/java/org/tcgscan/app/ui/onboarding/OnboardingViewModel.kt, app/src/main/java/org/tcgscan/app/data/AppPrefs.kt
do: Three steps: the « projet de fan non affilié » notice, the pack choice (FR / JP with sizes from the
  manifest, at least one required), then the download with progress and a done state. No network ⇒
  explanatory screen with « Réessayer » and a « continuer sans pack » exit where scanning is disabled
  (EC-1). `AppPrefs` (DataStore) stores `onboardingDone` and `defaultScanLang`; `MainActivity` routes to
  `OnboardingRoute` while it is false (edit `MainActivity.kt` only, not the NavHost).
exit: `./gradlew assembleDebug` succeeds; the onboarding start destination is decided from `AppPrefs` and
  the fan-project notice string is shown before the pack choice.

### T22: Backup — JSON/CSV export and JSON import  [todo]  (lite: no)
deps: T4
files: app/src/main/java/org/tcgscan/app/data/backup/BackupModels.kt, app/src/main/java/org/tcgscan/app/data/backup/BackupExporter.kt, app/src/main/java/org/tcgscan/app/data/backup/BackupImporter.kt, app/src/main/java/org/tcgscan/app/ui/backup/BackupRoute.kt, app/src/main/java/org/tcgscan/app/ui/backup/BackupViewModel.kt
do: `BackupModels` = `@Serializable` envelope `{schemaVersion, exportedAt, ownerships:[{lang,cardId,
  physicalLang,addedAt}], lists:[{name,isSystem,cards:[...]}]}`; no scan photos (FR-27).
  Exporter writes JSON and a flat CSV (`lang,card_id,physical_lang,added_at`) through
  `ACTION_CREATE_DOCUMENT` (SAF); on any failure it closes and deletes the target so no partial file is
  presented as valid (EC-19). Importer reads via `ACTION_OPEN_DOCUMENT`, refuses a corrupt body or a
  `schemaVersion > AppConfig.DB_SCHEMA` with no change at all (EC-18), then offers **fusion** (union) or
  **remplacement** after a confirmation dialog showing a diff summary (added/removed ownerships, lists)
  and applies it in one transaction. Imported ids absent from the pack are kept (EC-18).
exit: `./gradlew assembleDebug` succeeds; the importer parses fully and computes the summary before any
  write, and `BackupExporter` has no reference to the scan photo directory.

### T23: Bounded image loading with fallbacks  [done]  (lite: yes)
deps: T2
files: app/src/main/java/org/tcgscan/app/ui/image/ImageLoaderProvider.kt, app/src/main/java/org/tcgscan/app/ui/image/CardImage.kt
do: Build a Coil 2.7.0 `ImageLoader` with the shared OkHttpClient and a disk cache capped at
  `AppConfig.IMAGE_CACHE_BYTES` (256 MB) plus a `clear()` helper for Settings (FR-25).
  `CardImage(lang, cardId, imageUrl, scanPhotoPath, name, localId)` composable requests
  `"$imageUrl/low.webp"`, falls back to the local scan photo, then to a text-only card showing name and
  number — never an error state (FR-26/EC-15).
exit: `./gradlew assembleDebug` succeeds; the composable has exactly three fallback branches and the
  loader sets an explicit `maxSizeBytes`.

### T24: Pipeline scaffold and TCGdex client  [done]  (lite: no)
deps: T3
files: pipeline/requirements.txt, pipeline/tcgscan_pipeline/__init__.py, pipeline/tcgscan_pipeline/config.py, pipeline/tcgscan_pipeline/tcgdex.py, pipeline/README.md
do: `requirements.txt` pins `requests==2.32.*`, `numpy>=2.0`, `onnxruntime==1.31.1`,
  `opencv-python-headless==4.12.0.88` (all verified installable on Python 3.14).
  `config.py` holds the API base `https://api.tcgdex.net/v2`, languages `["fr","ja"]`, excluded series
  `{"tcgp"}`, image suffix `/low.webp`, embed model id/sha256, pack schema 1, the 2 % loss threshold and
  the cache paths (`pipeline/.cache/`).
  `tcgdex.py`: a `Session`-based client with a 150 ms minimum inter-request delay (FR-21), 5 retries with
  exponential backoff, and an explicit non-JSON branch — a body that fails `json.loads` (the measured
  `no available server`) is treated as retryable, and a final failure raises `TcgdexUnavailable`.
  Methods `series()`, `series_detail(id)`, `set_detail(id)`.
exit: `python3 -m py_compile pipeline/tcgscan_pipeline/*.py` passes and
  `python3 -m tcgscan_pipeline.tcgdex --probe fr` (a small `__main__` guard) prints the series count for FR.

### T25: Catalogue crawl and image cache  [done]  (lite: no)
deps: T24
files: pipeline/tcgscan_pipeline/catalog.py, pipeline/tcgscan_pipeline/images.py
do: `catalog.py`: `crawl(lang)` → `series()` → skip ids in `EXCLUDED_SERIES` (FR-20/D11) → for each kept
  series `series_detail` for its set ids → `set_detail` for every set, yielding `SetRow` and `CardRow`
  dataclasses (`card_id`, `local_id`, `name`, `name_norm`, `set_id`, `image_url`, `has_image`).
  `name_norm` **must** reimplement T12's `TextNormalizer` rule exactly (NFD, strip marks, lowercase,
  collapse non-alphanumerics) — reference docs/pack-format.md.
  `images.py`: download `{image}/low.webp` into `pipeline/.cache/images/<lang>/<card_id>.webp`, skip when
  present, honour the rate limiter, tolerate 404 (card simply has no image), and return the local path.
exit: `python3 -m py_compile` passes and `python3 -m tcgscan_pipeline.catalog --lang fr --max-sets 2`
  prints card rows with no `tcgp` set among them.

### T26: Embedding stage with cross-run cache  [todo]  (lite: no)
deps: T24, T6
files: pipeline/tcgscan_pipeline/embed.py, pipeline/tcgscan_pipeline/embed_cache.py
do: `embed.py` loads `app/src/main/assets/models/embed_dinov2s_int8.onnx` (same file as the app, D6),
  verifies its sha256 against `tools/models.lock`, and implements `embed_image(path) -> np.ndarray(384)`
  with the exact D6 preprocessing (`cv2.imread` → RGB → `cv2.resize(...,(224,224),INTER_LINEAR)` →
  /255 → ImageNet norm → NCHW), taking `last_hidden_state[0,0,:]` and L2-normalising; plus
  `quantize_int8(vec) -> bytes` (round(v*127), clip to [-127,127]).
  `embed_cache.py` is a SQLite cache `pipeline/.cache/embeddings.sqlite`
  (`key = (card_id, lang, image_sha256, model_sha256)` → int8 blob) so unchanged cards are never
  re-embedded across runs (FR-21).
  Optional `--en-image-fallback` hook per D15, default off, tagging rows `has_embed=2`.
exit: `python3 -m py_compile` passes and `python3 -m tcgscan_pipeline.embed --image <any webp from the
  cache>` prints a 384-length vector whose L2 norm is 1.0 ± 1e-3.

### T27: Pack builder  [todo]  (lite: no)
deps: T25, T26
files: pipeline/tcgscan_pipeline/packbuild.py
do: `build_pack(lang, out_dir)` creates `pack.db` with the exact DDL of docs/pack-format.md (D9), fills
  `pack_meta`, `sets`, `cards`, `embeddings` (int8 blobs for cards with an image) and `ocr_tokens`
  (localId, `localId/total`, set id, promo forms), creates the indices, `VACUUM`s, then gzips to
  `pack-<lang>.db.gz`. It writes no image bytes and asserts that no `tcgp` set reached the pack
  (AC-6 behaviour, enforced in code). Returns `(gz_path, bytes, sha256, card_count)`.
exit: `python3 -m py_compile` passes and `python3 -m tcgscan_pipeline.packbuild --lang fr --max-sets 2
  --out /tmp/packtest` produces a `pack-fr.db.gz` whose contained db has non-empty `cards`,
  `embeddings` and `ocr_tokens` tables and `pack_meta.pack_schema = 1`.

### T28: Manifest, 2 % guard, release gate and CLI  [todo]  (lite: no)
deps: T27
files: pipeline/tcgscan_pipeline/manifest.py, pipeline/tcgscan_pipeline/guard.py, pipeline/tcgscan_pipeline/cli.py
do: `manifest.py` writes `manifest.json` per docs/pack-format.md (schema 1, version = build date,
  per-pack file/bytes/sha256/cardCount/packSchema/installedBytes) and can read the previous published
  manifest from `https://github.com/<REPO>/releases/latest/download/manifest.json` (repo from the
  `TCGSCAN_REPO` env var, default the D13 placeholder), tolerating a 404 for the very first run.
  `guard.py check(prev, new)` fails when any language loses > 2 % of its cards, when a pack is missing,
  or when a crawl raised `TcgdexUnavailable` — and the gate is **all-or-nothing**: either both packs and
  the manifest are emitted into `dist/`, or `dist/` is left empty and the process exits non-zero so the
  previous release stays in place (FR-22, AC-7 behaviour).
  `cli.py` = `python -m tcgscan_pipeline build [--langs fr,ja] [--out dist] [--max-sets N]
  [--en-image-fallback]` orchestrating crawl → images → embed → pack → manifest → guard, with progress
  logging and a non-zero exit on any failure.
exit: `python3 -m py_compile` passes and `python3 -m tcgscan_pipeline build --langs fr --max-sets 1
  --out /tmp/packdry` exits 0 with `manifest.json` + `pack-fr.db.gz` in `/tmp/packdry` whose sha256
  matches the manifest entry; running the same command with a forced 1-card previous manifest exits
  non-zero and leaves `/tmp/packdry2` empty.

### T29: GitHub Actions workflows  [todo]  (lite: no)
deps: T28, T2
files: .github/workflows/packs.yml, .github/workflows/release-apk.yml
do: `packs.yml`: `schedule: cron "0 4 * * 1"` + `workflow_dispatch`; ubuntu-latest,
  `actions/setup-python@v5` with **3.12**, `pip install -r pipeline/requirements.txt`,
  `actions/cache@v4` on `pipeline/.cache/embeddings.sqlite` and `pipeline/.cache/images`
  (key `packs-<hash of requirements>-<run week>`, restore-keys prefix) to reuse embeddings (FR-21),
  `bash tools/fetch_models.sh`, then `python -m tcgscan_pipeline build --langs fr,ja --out dist` with
  `TCGSCAN_REPO: ${{ github.repository }}`. Upload `dist/*` to a release tagged `packs-<date>` with
  `softprops/action-gh-release` **only** when the previous step succeeded (`if: success()`), so a guard
  failure publishes nothing (FR-22).
  `release-apk.yml`: `workflow_dispatch` + tag `v*`; JDK 21 (`actions/setup-java@v4`, temurin),
  `android-actions/setup-android@v3`, `bash tools/fetch_models.sh`, decode
  `secrets.TCGSCAN_KEYSTORE_B64` to a temp keystore, export the four D14 env vars,
  `./gradlew assembleRelease`, attach both ABI APKs to the release. Document both workflows and the
  required secrets in README.
exit: `python3 -c "import yaml,sys;[yaml.safe_load(open(f)) for f in sys.argv[1:]]"
  .github/workflows/packs.yml .github/workflows/release-apk.yml` exits 0; the upload step in packs.yml
  is guarded by `if: success()` and no job in either file runs a test task.

### T30: FOSS and permission audit script  [todo]  (lite: yes)
deps: T2
files: tools/check-foss.sh
do: Bash script, `set -euo pipefail`: run
  `./gradlew :app:dependencies --configuration releaseRuntimeClasspath` and fail if the output matches
  `com\.google\.android\.gms|com\.google\.mlkit|com\.google\.firebase` (AC-2); then run
  `./gradlew :app:processReleaseManifest` and fail if the merged manifest at
  `app/build/intermediates/merged_manifest/release/AndroidManifest.xml` declares any `uses-permission`
  other than CAMERA, INTERNET, ACCESS_NETWORK_STATE (AC-3), or declares
  `ai.onnxruntime.TelemetryInitializer` (D5). Print a clear pass/fail summary.
exit: `bash tools/check-foss.sh` exits 0.

### T31: Final wiring pass and manual-debug checklist  [todo]  (lite: no)
deps: T15, T16, T17, T18, T19, T20, T21, T22, T29, T30
files: app/src/main/java/org/tcgscan/app/TcgScanApp.kt, app/src/main/java/org/tcgscan/app/di/AppContainer.kt, app/src/main/res/values/strings.xml, docs/manual-debug.md
do: Introduce (or finish) the hand-rolled `AppContainer` holding the single instances of
  `AppDatabase`, OkHttpClient, `PackRepository`, `PackCatalog` cache, `EmbeddingIndex` cache,
  `OnnxEnv`, `ScanPipeline`, repositories, and expose it from `TcgScanApp`; replace any remaining
  ad-hoc construction in the ViewModels with it. Sweep `strings.xml` for leftover hardcoded French
  text in composables. Write `docs/manual-debug.md`: the ordered manual checklist mapping each
  deferred AC (AC-5 to AC-19) to the gesture that exercises it, the `adb` commands to install the
  arm64 APK and push a locally built pack into `/data/data/org.tcgscan.app/files/packs/fr/pack.db`,
  and the « set AppConfig.GITHUB_REPO » reminder.
exit: `./gradlew assembleDebug assembleRelease` both succeed, `bash tools/check-foss.sh` exits 0, and
  `grep -rn "\"[A-ZÀ-Ü][a-zà-ü ]\{6,\}\"" app/src/main/java --include=*.kt | grep -v strings` returns
  nothing meaningful (no stray UI literals).

---

## Suggested parallel batches
- **B0**: T1 → then T2 (serial, everything depends on them). T3 can run concurrently with T1/T2.
- **B1** (parallel): T4, T5, T6, T7, T23, T24
- **B2** (parallel): T8, T9, T11, T12, T25, T30
- **B3** (parallel): T10, T13, T22, T26
- **B4** (parallel): T14, T20, T27
- **B5** (parallel): T15, T16, T21, T28
- **B6** (parallel): T17, T18, T19, T29
- **B7**: T31

## OPEN (decisions needing the user — plan is blocked on these)
- None. One value must be set by the user *after* the run, but it does not block implementation:
  `AppConfig.GITHUB_REPO` is shipped as the placeholder `"CHANGE-ME-OWNER/tcg-scan-app"` and must be
  replaced with the real `owner/repo` once the GitHub repository exists (see T2, T20, T31).
