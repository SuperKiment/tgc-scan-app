# ML models

TCG Scan runs **all** inference on the device. Five model/dictionary assets are shipped inside the
APK; none of them is downloaded at runtime, so scanning works offline immediately after install and
the model and code versions can never drift apart.

Every asset below is **Apache-2.0** licensed, which is compatible with the GPL-3.0 of this project
(NFR-1), and every one of them was verified by actually running it (shapes and timings are measured,
not quoted).

`tools/models.lock` holds the same `<asset-name> <sha256> <bytes> <url>` values and is the machine
readable source for `tools/fetch_models.sh`. **This file and `tools/models.lock` must agree.**

---

## 1. Visual embedding model

| asset | source URL | bytes | sha256 |
|---|---|---|---|
| `embed_dinov2s_int8.onnx` | `https://huggingface.co/onnx-community/dinov2-small/resolve/main/onnx/model_quantized.onnx` | 24 446 700 | `c179f8f7f592449c4c1bca4cd124a7538021428c5ffb89afde9503935b197efb` |

- **Model**: int8 ONNX export of `facebook/dinov2-small`.
- **Licence**: Apache-2.0 (both the original weights and the ONNX export).
- **I/O**: input `pixel_values (1, 3, 224, 224) float32` → output `last_hidden_state (1, 257, 384)`.
- **Measured**: 47 ms/image on the x86 build machine with 2 threads.
- **Embedding**: L2-normalised **CLS token**, `last_hidden_state[0, 0, :]`, 384 dims. The full
  preprocessing and int8 encoding contract is in `docs/pack-format.md` §3.
- **Used by**: the app (`scan/Embedder.kt`) **and** the CI pipeline
  (`pipeline/tcgscan_pipeline/embed.py`) — the exact same file, so pack vectors and on-device
  vectors are comparable.
- **Mirror** if the repository disappears: `Xenova/dinov2-small`, `onnx/model_quantized.onnx` (same
  export).

## 2. OCR models (PP-OCR)

| asset | source URL | bytes | sha256 |
|---|---|---|---|
| `ocr_det.onnx` | `https://huggingface.co/SWHL/RapidOCR/resolve/main/PP-OCRv4/ch_PP-OCRv4_det_infer.onnx` | 4 745 517 | `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9` |
| `ocr_rec_latin.onnx` | `https://huggingface.co/tobiichioriguchi/latin_PP-OCRv3_mobile_rec_onnx/resolve/main/inference.onnx` | 8 993 434 | `2bdd80b2a52fb2bb08b7407867eeb04fc8059b828dd46e500a9d48ba76ace9d8` |
| `ocr_rec_japan.onnx` | `https://huggingface.co/tobiichioriguchi/japan_PP-OCRv3_mobile_rec_onnx/resolve/main/inference.onnx` | 10 089 078 | `2e397c27461751befc82eca78c9b19e87322ebee34e7316468b21856f9977d64` |
| `dict_latin.txt` | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/dict/latin_dict.txt` | 468 | `8e6d4e3629788c35c31f7e530287d6147b549bb7a265bd6708bb281134429e2c` |
| `dict_japan.txt` | `https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/release/2.7/ppocr/utils/dict/japan_dict.txt` | 17 332 | `1dcfcb41eec90576a945b3084f22ade11ced506e24f14879245b071698f308e8` |

All five are Apache-2.0 (PaddleOCR and its derived exports).

### Measured I/O shapes

| asset | input | output | notes |
|---|---|---|---|
| `ocr_det.onnx` | `x (1, 3, H, W) float32`, H and W multiples of 32 | sigmoid probability map `(1, 1, H, W)` | 62 ms at 640×640 on the x86 build machine |
| `ocr_rec_latin.onnx` | `x (N, 3, 48, W) float32` | `(N, T, 187)` | 187 = 1 blank + 185 dict chars + 1 space, matches `dict_latin.txt` (185 lines) |
| `ocr_rec_japan.onnx` | `x (N, 3, 48, W) float32` | `(N, T, 4401)` | 4401 = 1 blank + 4399 dict chars + 1 space, matches `dict_japan.txt` (4399 lines) |

The CTC decoder asserts `dict.size + 2 == outputClasses` for both recognisers; index 0 is the blank
and the last class is the space.

### Why the *latin* recogniser, not `en_PP-OCRv3`

`en_dict.txt` has 95 characters and no French accents, which breaks names such as « Dracaufeu » or
« Métamorph ». The `latin` recogniser's 185-character dictionary covers the French alphabet.

### Mirrors

If one of the Hugging Face repositories vanishes (same `inference.onnx`, same Apache-2.0 licence):

- `itextresearch/itext-latin_PP-OCRv3_mobile_rec_infer`
- `itextresearch/itext-japan_PP-OCRv3_mobile_rec_infer`
- `cycloneboy/japan_PP-OCRv4_rec_infer`

A failed fetch must make the build **fail loudly** (`tools/fetch_models.sh`): recognition is never
stubbed out.

---

## 3. Shipping and size budget

All five assets (~48 MB) live in `app/src/main/assets/models/` and are fetched by
`bash tools/fetch_models.sh`, which verifies every sha256 against `tools/models.lock` and skips
files that already match. `ModelAssets` copies them once to `<filesDir>/models/` at runtime, because
ONNX Runtime needs a file path for large models.

| component | arm64-v8a | armeabi-v7a |
|---|---|---|
| ONNX Runtime Android 1.31.1 | ~34 MB | ~34 MB |
| OpenCV 4.12.0 native libs | ~23 MB | ~15 MB |
| model assets | ~48 MB | ~48 MB |
| **expected ABI-split APK** | **~95 MB** | **~80 MB** |

Rationale for shipping instead of downloading with the packs: scanning works offline right after
install, and a pack can never be paired with a model it was not built against (see the
`pack_meta.embed_model` key in `docs/pack-format.md`).

ONNX Runtime's AAR declares `<provider android:name="ai.onnxruntime.TelemetryInitializer">`; it is
removed from the merged manifest with `tools:node="remove"` (NFR-5), and `tools/check-foss.sh`
fails the build if it ever reappears.

---

## 4. Licence summary (FR-29 / NFR-1)

| component | version | licence |
|---|---|---|
| TCG Scan itself | — | GPL-3.0-only |
| `facebook/dinov2-small` (int8 ONNX export) | — | Apache-2.0 |
| PP-OCRv4 detection, PP-OCRv3 latin/japan recognition, PaddleOCR dictionaries | — | Apache-2.0 |
| ONNX Runtime Android | 1.31.1 | MIT |
| OpenCV (`org.opencv:opencv`) | 4.12.0 | Apache-2.0 |
| AndroidX (Compose, CameraX, Room, DataStore, WorkManager, Lifecycle, Navigation) | see `gradle/libs.versions.toml` | Apache-2.0 |
| Kotlin stdlib, coroutines, kotlinx.serialization | see `gradle/libs.versions.toml` | Apache-2.0 |
| OkHttp | 4.12.0 | Apache-2.0 |
| Coil | 2.7.0 | Apache-2.0 |
| Card data | TCGdex API | see <https://tcgdex.dev> |

No Google Play Services, ML Kit or Firebase component, transitive included (NFR-2, AC-2).
