# app

> Repository `tgc-scan-app`, path `tgc-scan-app/app/`. Checked on 2026-11-12 from `app/build.gradle.kts`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`, `AppConfig.kt`, `TcgScanApp.kt`, `MainActivity.kt`, `ui/Routes.kt`, `data/db/*`, `app/schemas/`, `app/src/main/assets/models/`, `README.md`, `tools/check-foss.sh` (working tree of branch `feat/v1`, partly uncommitted).

## What it is for
Android app that scans French and Japanese Pokémon cards with the phone camera and files them into a local collection, wishlist and custom lists. Recognition is hybrid: a visual embedding of the straightened card is matched against the index of an installed language pack, and OCR of the card text confirms or disambiguates. It works offline once a pack is installed; the UI is in French (README).

## Tech
- Type: Android application, single Gradle module `:app` (`settings.gradle.kts`), build file `app/build.gradle.kts`, application id `org.tcgscan.app`, version 1.0.0 (versionCode 1).
- Versions (`gradle/libs.versions.toml`): AGP 8.7.3, Kotlin 2.1.0, KSP 2.1.0-1.0.29, Compose BOM 2024.12.01 (Material3 1.3.1), Room 2.6.1, ONNX Runtime Android 1.31.1, OpenCV 4.12.0, CameraX 1.4.2, OkHttp 4.12.0, Coil 2.7.0, DataStore 1.1.1.
- SDK: minSdk 26, compileSdk 35, targetSdk 35. JVM target 11 for Java and Kotlin, no `jvmToolchain` (only JDK 21 installed).
- Build: one APK per ABI (`arm64-v8a`, `armeabi-v7a`), no universal APK. Release has R8 minify and resource shrinking.
- Signing: release uses the `TCGSCAN_KEYSTORE_FILE`, `TCGSCAN_KEYSTORE_PASSWORD`, `TCGSCAN_KEY_ALIAS`, `TCGSCAN_KEY_PASSWORD` values (environment, then Gradle properties). If any is missing, the release build falls back to the debug keystore and logs a warning (`signingConfigs.findByName("release") ?: getByName("debug")`).
- Entry points: `TcgScanApp` (loads OpenCV with `OpenCVLoader.initLocal()`), `MainActivity` (portrait, Compose `setContent`, launches `TcgScanNavHost`); routes declared in `ui/Routes.kt` (scan, collection, catalog, lists, settings as bottom bar; review, set browse, card detail, list detail, about, backup as details).
- Permissions: CAMERA, INTERNET, ACCESS_NETWORK_STATE; the ONNX Runtime telemetry provider is removed from the manifest; `allowBackup` is false.
- Database: Room `AppDatabase`, schema version 1 (`AppConfig.DB_SCHEMA = 1`, `app/schemas/org.tcgscan.app.data.db.AppDatabase/1.json`), tables `owned_cards`, `card_lists`, `card_list_items`, `scan_sessions`, `scan_detections`. `DbProvider.MIGRATIONS` is empty and `fallbackToDestructiveMigration()` is not used.
- Assets (`app/src/main/assets/models/`): `embed_dinov2s_int8.onnx`, `ocr_det.onnx`, `ocr_rec_latin.onnx`, `ocr_rec_japan.onnx`, `dict_latin.txt`, `dict_japan.txt`.

Packages under `app/src/main/java/org/tcgscan/app/`:

| Package | Role (from file names and doc comments) |
|---|---|
| `.` | `AppConfig`, `TcgScanApp`, `MainActivity` |
| `scan/` | Embedding (`Embedder`, `Preprocess`), model loading (`ModelAssets`, `OnnxEnv`), tuning constants, scan photo storage |
| `scan/detect/` | Card quad detection, camera frame conversion, stabilizer, perspective warp |
| `scan/ocr/` | PP-OCR text detection and recognition, CTC decoding |
| `scan/text/` | Card text parsing, script detection, text normalisation |
| `scan/match/` | Candidate model and `Matcher` against the embedding index |
| `data/` | `CardListRepository` |
| `data/db/` | Room database, DAOs and entities (ownership, lists, scan sessions) |
| `data/pack/` | Pack catalogue reader, embedding index, install, install state, paths, repository |
| `data/net/` | HTTP client, manifest service and model, range downloader |
| `data/backup/` | Backup export and import and their models |
| `ui/` | Navigation host and routes |
| `ui/scan`, `ui/review` | Camera scan screen, review of detections |
| `ui/collection`, `ui/catalog`, `ui/lists` | Collection, pack catalogue browsing and card detail, custom lists |
| `ui/settings`, `ui/backup`, `ui/about`, `ui/onboarding` | Settings and pack management, backup screen, about, onboarding |
| `ui/image`, `ui/theme` | Card image loading, theme |

## Dependencies
- Runtime network: the pack manifest at `AppConfig.MANIFEST_URL`, derived from `AppConfig.GITHUB_REPO` (`https://github.com/<repo>/releases/latest/download/manifest.json`), and the pack archives it lists. `GITHUB_REPO` is `SuperKiment/tgc-scan-app` (`AppConfig.kt:13`); a fork must change it, and `isGithubRepoConfigured()` reports whether it still equals the placeholder.
- Card images: loaded through Coil only from the `https://assets.tcgdex.net/` prefix (`ui/image/CardImage.kt:115`), with a disk cache capped by `AppConfig.IMAGE_CACHE_BYTES` (256 MB).
- Local: Room database for user data; the installed pack is a SQLite file `<filesDir>/packs/<lang>/pack.db` (`data/pack/PackPaths.kt`) read through `PackCatalog`; ONNX models from assets.
- Relation with `pipeline/`: the app consumes the packs built by `pipeline/tcgscan_pipeline/packbuild.py` (`pack-<lang>.db.gz`, DDL from `docs/pack-format.md`, plus `manifest.json`). The app refuses a pack whose `pack_meta.pack_schema` is above `AppConfig.PACK_SCHEMA` (1).
- Libraries of note: ONNX Runtime and OpenCV (inference and vision), CameraX, Room, OkHttp, Coil, kotlinx.serialization, DataStore. No Google Play services, ML Kit or Firebase: `tools/check-foss.sh` fails on them in `releaseRuntimeClasspath`, and checks that the merged release manifest requests only the three allowed permissions (plus the androidx.core signature permission) and has no ONNX telemetry provider.

## Place in the whole
Only Gradle module of the repository (`include(":app")`). It is the consumer side of the data flow; the Python `pipeline/` is the producer, and they share the contract in `docs/pack-format.md`. A hand-written dependency container is mentioned as planned in `TcgScanApp.kt` (T31); no DI library is declared.

## Tests
Nothing found: there is no `app/src/test` or `app/src/androidTest` folder and no test dependency in `app/build.gradle.kts`. This is a project decision; verification is build based (`./gradlew assembleDebug`), and accuracy is checked manually (see the benchmark doc).

## Related docs
- [Pack format](../../../pack-format.md)
- [ML models](../../../models.md)
- [Benchmark](../../../benchmark.md)
