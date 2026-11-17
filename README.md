# TCG Scan

Scan and catalogue your Pokémon cards on Android — free, open source, offline.

TCG Scan recognises physical **French** and **Japanese** Pokémon cards in a burst, entirely on the
phone (no account, no server, no telemetry, no ads), and files them into a local collection with a
wishlist and custom lists. Recognition is hybrid: a visual fingerprint of the straightened card is
compared against the index shipped in a language pack, and on-device OCR of the name, number and set
code confirms or disambiguates the candidates. Nothing enters the collection without a confirmation
in the review screen.

The UI is in French. Distribution is **GitHub Releases only** (no Play Store, no F-Droid in v1).

> [!IMPORTANT]
> **Forks must change `AppConfig.GITHUB_REPO`.**
> `app/src/main/java/org/tcgscan/app/AppConfig.kt` sets `GITHUB_REPO = "SuperKiment/tgc-scan-app"`. It
> is the single constant from which the pack manifest URL is derived
> (`https://github.com/<REPO>/releases/latest/download/manifest.json`). A fork must replace it with its
> own `owner/repo`, otherwise the app downloads the packs of the upstream repository. The pack pipeline
> reads the same value from the `TCGSCAN_REPO` environment variable.

## Install

For Android users who just want the app (no build needed). Requires **Android 8.0 or newer**.

1. On your phone, open the [releases page](https://github.com/SuperKiment/tgc-scan-app/releases) and
   pick the newest app release (tags `v1.0.0`, `v1.1.0`, …).
   Releases named `packs-<date>` hold language packs, not the app: the app downloads them itself.
2. Download the APK that matches your CPU. There is no universal APK:
   - `app-arm64-v8a-release.apk`: almost every phone since about 2017. Try this one first.
   - `app-armeabi-v7a-release.apk`: old 32-bit phones only.
3. Open the downloaded file. When Android asks, allow your browser or file manager to install unknown
   apps (Settings > Apps > Special access > Install unknown apps), then install.
4. Play Protect may warn about an unknown developer: choose "Install anyway".

> [!WARNING]
> Installing the wrong APK makes the app stop at start. With a computer, you can check the CPU with
> `adb shell getprop ro.product.cpu.abi`, and install with `adb install -r app-arm64-v8a-release.apk`.

**First launch.** Onboarding offers to download the French and/or Japanese language pack (internet is
needed once: about 7 MB for FR, 4 MB for JA). After that everything works offline. The camera
permission is requested at the first scan. Packs are managed in Settings > « Packs de langues »
(Installer / Mettre à jour / Supprimer).

**Updating.** Install the newer APK over the old one; your data is kept. Release APKs are signed with
the project's release key, so a debug build (built from source) and a release APK cannot update each
other. In that case, first export your collection (Settings > « Sauvegarde et restauration » > export
JSON), uninstall the app, install the other build, then import the JSON again.

## Requirements

- **JDK 21** (the only JDK this project is configured against; Java/Kotlin target is 11).
- Android SDK with **platform 35** installed. `compileSdk`/`targetSdk` are 35, `minSdk` is 26
  (Android 8).
- Gradle is **not** required on the `PATH`: use the wrapper (Gradle 8.14).
- Python **3.12+** only if you want to run the pack pipeline locally.

## Build

The Android SDK location is not committed. Either export `ANDROID_HOME`, or create
`local.properties` in the repository root:

```properties
sdk.dir=/path/to/Android/Sdk
```

Fetch the ML model assets (~48 MB, verified against `tools/models.lock`), then build:

```bash
bash tools/fetch_models.sh
./gradlew assembleDebug      # debug APKs, per ABI
./gradlew assembleRelease    # release APKs, per ABI
```

APKs are split by ABI (no universal APK) and land in `app/build/outputs/apk/<variant>/`:
`app-arm64-v8a-<variant>.apk` and `app-armeabi-v7a-<variant>.apk`.

Release signing is read from the environment (or `~/.gradle/gradle.properties`):

| variable | meaning |
|---|---|
| `TCGSCAN_KEYSTORE_FILE` | path to the keystore |
| `TCGSCAN_KEYSTORE_PASSWORD` | keystore password |
| `TCGSCAN_KEY_ALIAS` | key alias |
| `TCGSCAN_KEY_PASSWORD` | key password |

When these are absent, `assembleRelease` falls back to the debug keystore and prints a warning, so
the build never breaks. **No key is generated or stored by this repository.**

A FOSS and permission audit is available:

```bash
bash tools/check-foss.sh
```

It fails if any Google Play Services, ML Kit or Firebase artefact appears in the release runtime
classpath, if the merged manifest declares a permission other than `CAMERA`, `INTERNET` and
`ACCESS_NETWORK_STATE`, or if the ONNX Runtime telemetry provider is not stripped.

## Language packs

Card data is **not** bundled in the APK. A weekly GitHub Actions workflow crawls the TCGdex API and
publishes one gzipped SQLite pack per language plus a `manifest.json` to GitHub Releases; the app
reads the manifest, shows the download size, and installs the pack atomically after a SHA-256 check.
Installing or updating a pack never touches your collection.

- Pack and manifest format, embedding contract and the 2 % release guard: **`docs/pack-format.md`**
  (authoritative).
- Shipped ML models, their sources, sizes, sha256 and licences: **`docs/models.md`**.
- Manual verification checklist and measured accuracy/latency: `docs/manual-debug.md`,
  `docs/benchmark.md`.

Build a pack locally:

```bash
python3 -m pip install -r pipeline/requirements.txt
python3 -m tcgscan_pipeline build --langs fr --out dist
```

## Continuous integration

| workflow | trigger | what it does |
|---|---|---|
| `.github/workflows/packs.yml` | weekly schedule + `workflow_dispatch` | builds the FR and JP packs and `manifest.json`, publishes them to a release **only** if the guard passed |
| `.github/workflows/release-apk.yml` | `workflow_dispatch` + `v*` tags | builds and signs the release APKs and attaches both ABI variants to the release |

Required repository secret for the APK workflow:

| secret | meaning |
|---|---|
| `TCGSCAN_KEYSTORE_B64` | base64 of the release keystore; decoded to a temporary file, then exposed as `TCGSCAN_KEYSTORE_FILE` |
| `TCGSCAN_KEYSTORE_PASSWORD` | keystore password |
| `TCGSCAN_KEY_ALIAS` | key alias |
| `TCGSCAN_KEY_PASSWORD` | key password |

The pack workflow needs no secret beyond the default `GITHUB_TOKEN`.

## Privacy

No account, no telemetry, no advertising. Scan photos stay in the app's private storage and are
never exported nor uploaded. The app requests only `CAMERA`, `INTERNET` and `ACCESS_NETWORK_STATE`;
the network is used solely to fetch the pack manifest, the packs, and card visuals from TCGdex.

## Licence and mentions

TCG Scan is licensed under the **GNU General Public License v3.0 only** — see [`LICENSE`](LICENSE).

> **Projet de fan non affilié à Nintendo, The Pokémon Company ou Creatures.**
> Données fournies par [TCGdex](https://tcgdex.dev).
>
> Fan project, not affiliated with, endorsed or sponsored by Nintendo, The Pokémon Company or
> Creatures Inc. Pokémon and Pokémon character names are trademarks of their respective owners. Card
> data is provided by TCGdex; card images are loaded from TCGdex on demand and are **never**
> redistributed in the app, in the packs or in this repository.

Open source licences of the dependencies and of the ML models are listed in
[`docs/models.md`](docs/models.md) and reproduced in the app's « À propos » screen: ONNX Runtime
(MIT), OpenCV, AndroidX/Jetpack Compose, CameraX, Room, OkHttp, Coil, Kotlin and its libraries
(Apache-2.0), DINOv2 and PP-OCR models and dictionaries (Apache-2.0).
