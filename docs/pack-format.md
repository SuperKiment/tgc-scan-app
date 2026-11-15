# Pack format (authoritative)

This document is the **authoritative** specification of the data artefacts exchanged between the
CI pipeline (`pipeline/tcgscan_pipeline`, which *writes* them) and the Android app
(`app/src/main/java/org/tcgscan/app/data/pack`, which *reads* them). Any disagreement between code
and this file is a bug in the code.

Current version: `pack_schema = 1`, `manifest.schema = 1`.

---

## 1. Artefacts

| artefact | produced by | consumed by | location on device |
|---|---|---|---|
| `manifest.json` | `pipeline/tcgscan_pipeline/manifest.py` | `data/net/ManifestService.kt` | not stored |
| `pack-fr.db.gz` | `pipeline/tcgscan_pipeline/packbuild.py` | `data/pack/PackInstaller.kt` | staged, then deleted |
| `pack-ja.db.gz` | idem | idem | idem |
| decompressed pack | `PackInstaller` | `data/pack/PackCatalog.kt`, `EmbeddingIndex.kt` | `<filesDir>/packs/<lang>/pack.db` |

One pack per language. Language codes are exactly the TCGdex ones: **`fr`** and **`ja`**
(`/v2/jp/...` returns 404, `/v2/ja/...` returns 200). The app labels them « Français » and
« Japonais ».

A pack is a **gzipped SQLite database**. It contains metadata, OCR search data and visual
embeddings. It contains **no image bytes** (TCGdex images are never redistributed — see
SPEC Non-Goals); only the image *URL* is stored.

The pack is opened read-only by the app (`SQLiteDatabase.openDatabase(..., OPEN_READONLY)`), never
through Room: it is produced externally and versioned by `pack_meta.pack_schema`, independently of
the app's own Room database (`AppConfig.DB_SCHEMA`).

**No FTS5.** FTS5 is not guaranteed in the SQLite shipped with Android 8 (API 26), so search is
implemented with `LIKE` queries over the normalised column `cards.name_norm` and over
`ocr_tokens.token`.

---

## 2. Schema (DDL, verbatim)

The pack builder must execute exactly these statements, in this order.

```sql
CREATE TABLE pack_meta (
    key   TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);

CREATE TABLE sets (
    id                  TEXT PRIMARY KEY NOT NULL,
    name                TEXT NOT NULL,
    series_id           TEXT NOT NULL,
    card_count_total    INTEGER NOT NULL,
    card_count_official INTEGER NOT NULL,
    release_date        TEXT,
    symbol_url          TEXT,
    logo_url            TEXT
);

CREATE TABLE cards (
    card_id   TEXT PRIMARY KEY NOT NULL,
    local_id  TEXT NOT NULL,
    name      TEXT NOT NULL,
    name_norm TEXT NOT NULL,
    set_id    TEXT NOT NULL REFERENCES sets(id),
    image_url TEXT,
    has_embed INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE embeddings (
    card_id TEXT PRIMARY KEY NOT NULL REFERENCES cards(card_id),
    vec     BLOB NOT NULL
);

CREATE TABLE ocr_tokens (
    card_id TEXT NOT NULL REFERENCES cards(card_id),
    token   TEXT NOT NULL,
    PRIMARY KEY (card_id, token)
);

CREATE INDEX idx_cards_name_norm ON cards (name_norm);
CREATE INDEX idx_cards_local_id  ON cards (local_id);
CREATE INDEX idx_cards_set_id    ON cards (set_id);
CREATE INDEX idx_ocr_tokens_token ON ocr_tokens (token);
```

After filling the tables the builder runs `VACUUM`, then gzips the file to `pack-<lang>.db.gz`.

### 2.1 `pack_meta` keys (all required)

| key | example | meaning |
|---|---|---|
| `pack_schema` | `1` | schema version of this file; the app **refuses** a pack whose value is greater than `AppConfig.PACK_SCHEMA` (EC-17) |
| `lang` | `fr` | TCGdex language code of the pack |
| `built_at` | `2026-11-08T04:12:33Z` | UTC ISO-8601 build timestamp |
| `card_count` | `20226` | number of rows in `cards` |
| `embed_model` | `embed_dinov2s_int8.onnx@c179f8f7…` | asset name + sha256 of the model that produced `embeddings` |
| `embed_dim` | `384` | number of int8 components per vector |

`embed_model` lets the app detect a pack whose vectors were produced by a different model than the
one bundled in its assets; such a pack must not be used for visual matching.

### 2.2 `cards.has_embed`

| value | meaning | may be pre-checked after a scan? |
|---|---|---|
| `0` | no visual reference (no TCGdex image) — OCR-only card (FR-3) | **never** |
| `1` | embedding computed from the image of this very card, in this language: the TCGdex image or, for a `ja` card TCGdex has none for, the Limitless TCG image (`--jp-extra-images`, §4.9, default off) | yes, if the confidence policy agrees |
| `2` | embedding computed from the image of the same card in another language: the same card id in another TCGdex language (`en`, `de`, `it`, `es`, `pt`, first one with an image; `--en-image-fallback`, D15/FR-4, default off) or, for a `fr` card no TCGdex language has an image for, the EN image of pokemontcg.io (`--fr-extra-images`, §4.10, default off) | **never** (down-weighted) |

A row has an `embeddings` entry if and only if `has_embed != 0`.

### 2.3 `ocr_tokens`

Search keys for the OCR path, lowercased, one row per token:

- the bare `local_id` (`136`, `sv043`, `xy12`, `h7`);
- the printed fraction `local_id/card_count_total` (`136/201`) — see §5;
- the set id (`swsh3`);
- promo/alphanumeric forms of the `local_id` with separators removed.

---

## 3. Embedding contract

Both sides — the CI pipeline (`pipeline/tcgscan_pipeline/embed.py`) and the device
(`app/src/main/java/org/tcgscan/app/scan/Embedder.kt` + `Preprocess.kt`) — run **the same ONNX
file**, `app/src/main/assets/models/embed_dinov2s_int8.onnx` (int8 export of `facebook/dinov2-small`,
Apache-2.0; see `docs/models.md` for URL, size and sha256). Pack vectors and on-device vectors are
therefore directly comparable.

### 3.1 Preprocessing (identical on both sides)

1. Input is the perspective-corrected card crop (device) or the cached `low.webp` image (pipeline),
   in RGB.
2. `cv2.resize(img, (224, 224), interpolation=cv2.INTER_LINEAR)` — aspect ratio is **squashed**, no
   letterbox, no centre crop. OpenCV is used on both sides so the resampling is bit-comparable.
3. `float32`, divide by `255`.
4. ImageNet normalisation: mean `(0.485, 0.456, 0.406)`, std `(0.229, 0.224, 0.225)`, per channel,
   RGB order.
5. Layout NCHW → tensor `pixel_values` of shape `(1, 3, 224, 224)`, `float32`.

### 3.2 Inference and pooling

- Output `last_hidden_state` has shape `(1, 257, 384)`.
- The embedding is the **CLS token**: `last_hidden_state[0, 0, :]` → 384 floats.
- The vector is **L2-normalised** (`v / ||v||₂`), so a dot product is a cosine.

### 3.3 int8 encoding of `embeddings.vec`

- `vec` is exactly `embed_dim` = 384 bytes, one signed byte per component, in component order.
- Encoding: `round(v_i * 127)`, clipped to `[-127, 127]`, stored as a signed 8-bit value.
- Decoding is not needed: similarity is computed in integer space.
  `cosine(q, c) ≈ Σ q_i·c_i / (127 · 127)` where `q` and `c` are the stored int8 values — i.e. the
  app quantises the query vector the same way and divides the integer dot product by `127²`.
- Expected error of the int8 round-trip on a cosine is below `1e-2`, well under the decision
  margins of `scan/ScanTuning.kt`.

### 3.4 Index strategy

~17 k French cards with an image × 384 B ≈ 6.6 MB of vectors. The app loads the whole `embeddings`
table into **one direct `ByteBuffer`** with a parallel `card_id` array and does a **brute-force**
int8 dot product over it (≈ 6.6 M MACs, a few milliseconds). There is **no ANN index** in the pack
and none is to be added without changing `pack_schema`.

---

## 4. Catalogue crawl rules

Base URL `https://api.tcgdex.net/v2`, one language at a time (`fr`, `ja`).

1. `GET /v2/{lang}/series` → series list.
2. Drop every series whose id is in the exclusion set `{"tcgp"}`. TCG Pocket is a digital game and
   is excluded **by series**, never by a frozen set list (FR-20). The pack builder asserts that no
   excluded set reached the pack.
3. `GET /v2/{lang}/series/{series_id}` → the set ids of that series.
4. `GET /v2/{lang}/sets/{set_id}` → the set *and its full card list* (`id`, `localId`, `name`,
   `image`) plus `cardCount` (`total`, `official`). **No per-card request is ever made**: ~1 + ~20 +
   ~203 requests per language (plus, with `--en-image-fallback`, one set request per fallback
   language and set that has a card without a visual).
5. `cards.image_url` stores the TCGdex image *stem* as returned by the API; the concrete asset is
   `{image}/low.webp` (`high.webp` and `low.png` also exist). A card whose `image` field is absent
   gets `image_url = NULL` and `has_embed = 0`.
   A stored stem **must start with the literal prefix `https://assets.tcgdex.net/`**, compared byte
   for byte, with **no case folding** — `app/src/main/java/org/tcgscan/app/ui/image/CardImage.kt`
   requests a visual only for such a stem, because `image_url` is pack data that Coil would
   otherwise resolve as a `file://` or `content://` URI. A stem the app refuses is of no use, so
   the crawl stores it as `image_url = NULL` with `has_embed = 0` and logs a warning, and the pack
   builder **refuses** a database in which any row has a non-`NULL` `image_url` outside the prefix.
6. Rate limiting (FR-21): minimum 150 ms between two requests, 5 attempts with exponential backoff
   (the first try plus 4 retries, 1/2/4/8 s capped at 30 s).
7. A body that fails `json.loads` — the measured intermittent `no available server` plain-text
   response — is treated as a **retryable** error, not as data. A final failure raises
   `TcgdexUnavailable`, which makes the release gate fail (§6).
8. Images are cached under `pipeline/.cache/images/<lang>/<card_id>.webp` and embeddings under
   `pipeline/.cache/embeddings.sqlite`, keyed by
   `(card_id, lang, image_sha256, model_sha256)`, so unchanged cards are never re-downloaded or
   re-embedded across runs (FR-21).
9. Optional extra source of `ja` visuals, Limitless TCG (`--jp-extra-images`, default off). For a
   `ja` card TCGdex has no image for, the pipeline reads the set page
   `https://limitlesstcg.com/cards/jp/<CODE>` (one request per set, memoised; `<CODE>` is the
   TCGdex set id, except `SV-P` → `SVP` and `M-P` → `MP`) and downloads the card's PNG (`_SM`
   size, 274×381 px, the closest to TCGdex's `low.webp`). Cards are matched on `int(localId)`
   (the Limitless number is not zero-padded). Images are cached as
   `pipeline/.cache/images/ja-limitless/<card_id>.png`. The vector is tagged `has_embed = 1`: the
   image is the JP print itself. Politeness: ≥ 1 s between two requests, a generic User-Agent, no
   redirect followed; an image that does not decode is never cached, and the source switches
   itself off after 3 failed requests in a row or 150 minutes of run time. The source is
   **optional**: a missing page (about 64 JA sets have none), a
   missing card or any network or parse failure only leaves the card without a visual
   (`has_embed = 0`); it never fails the build nor counts toward the 2 % guard. The pack format is
   unchanged and no image byte enters a pack.
10. Optional extra source of `fr` visuals, pokemontcg.io (`--fr-extra-images`, default off). For a
    `fr` card still without a visual after the other-language fallback (§4.4), whose TCGdex set is
    in `config.PTCGIO_SET_ALIASES` (TCGdex FR set id → pokemontcg.io set id; only sets whose
    numbering was checked to be the same: Shiny Vaults, Trainer Galleries, Galarian Gallery,
    Celebrations Classic Collection, McDonald's 2011–2012 and 2014–2017, 2021–2022, EX Trainer Kits,
    some promos…), the pipeline reads the open `pokemon-tcg-data` card list of the set
    (`https://raw.githubusercontent.com/PokemonTCG/pokemon-tcg-data/master/cards/en/<set>.json`,
    one request per set, memoised) and downloads the `images.small` PNG of the card from
    `https://images.pokemontcg.io/`. The pokemontcg.io API is never called. The card is matched on
    its `localId` with the digit padding ignored (`SV004` = `SV4`, `H01` = `H1`, `%3F` = `?`); the
    Celebrations Classic Collection, numbered differently, uses `config.PTCGIO_NUMBER_OVERRIDES`.
    Sets hosted on another image host (Mega Evolution, 30th Celebration) are not covered. Images are
    cached as `pipeline/.cache/images/en-ptcgio/<card_id>.png`. The vector is tagged
    `has_embed = 2`: the image is an EN print of the same card. Politeness: ≥ 0.25 s between two
    requests, a generic User-Agent, no redirect followed; an image that does not decode is never
    cached, and the source switches itself off after 3 failed requests in a row or 30 minutes of run
    time. The source is **optional**: an unmapped set, a card the data does not list or any network
    or parse failure only leaves the card without a visual (`has_embed = 0`); it never fails the
    build nor counts toward the 2 % guard. The pack format is unchanged and no image byte enters a
    pack.

---

## 5. Printed set code and OCR confirmation

TCGdex exposes **no printed extension code**, and none is invented. The OCR confirmation therefore
matches on what is actually printed on the card:

- the `local_id` alone (« 136 »), including promo/alphanumeric forms (`SV043`, `XY12`, `H7`);
- the printed fraction `local_id` / `card_count_total` (« 136/201 »), built from
  `sets.card_count_total`;
- the **set id** as a token (`swsh3`), which often matches the small printed code;
- the **set name**, compared against the normalised card text.

All of these live in `ocr_tokens` (§2.3) except the set name, which is read from `sets.name`.

### 5.1 Canonical name normalisation (`name_norm`)

`cards.name_norm` is produced by the following rule, which
`app/src/main/java/org/tcgscan/app/scan/text/TextNormalizer.kt` (device) and
`pipeline/tcgscan_pipeline/catalog.py` (CI) must implement **identically** — this is the single
canonical definition:

1. Unicode **NFD** decomposition.
2. Remove all combining marks (Unicode category `Mn`), so « Dracaufeu ex » and accented French
   names collapse to their unaccented form.
3. Lowercase.
4. Replace every run of characters that are not `[a-z0-9]` — and, for Japanese, not kana/CJK — by a
   single space, then trim.

Japanese text keeps its kana and CJK code points: only marks and punctuation are removed.

Two consequences of that rule for Japanese are **deliberate** and part of the contract — both
implementations must reproduce them, and neither is a bug to be fixed:

- step 2 removes the dakuten and handakuten along with every other combining mark, because NFD
  decomposes `ガ` into `カ` + U+3099. `ガブリアス` therefore normalises to **`カフリアス`**, not to
  `ガブリアス`. Voicing is simply ignored on both sides of the comparison, which costs nothing to a
  `LIKE` search and keeps the device and the CI rule identical;
- the decomposition is **NFD, not NFKD**, so compatibility forms are never folded: fullwidth Latin
  and digits (`Ｄ`, `１`) stay distinct from `D` and `1` — being neither `[a-z0-9]` nor kana/CJK,
  they are swallowed by step 4 — and halfwidth katakana (`ｶ`) stays its own code point instead of
  folding to `カ`.

Switching to NFKD would change `name_norm` for every affected card and break the device/CI
equality, so it is a `pack_schema` matter, never a one-sided fix.

A `LIKE '%' || :q || '%'` query over `name_norm`, with `q` normalised by the same rule, is the only
name search path (no FTS5, §1).

---

## 6. `manifest.json`

Published as a GitHub Release asset and read by the app at
`https://github.com/<REPO>/releases/latest/download/manifest.json` — a plain redirect, no GitHub
API, no token, no rate limit. `<REPO>` is the single constant `AppConfig.GITHUB_REPO`.

```json
{
  "schema": 1,
  "version": "2026-11-08",
  "generatedAt": "2026-11-08T04:12:33Z",
  "packs": {
    "fr": {
      "file": "pack-fr.db.gz",
      "bytes": 9123456,
      "sha256": "9f2b…",
      "cardCount": 20226,
      "packSchema": 1,
      "installedBytes": 28123456
    },
    "ja": {
      "file": "pack-ja.db.gz",
      "bytes": 3456789,
      "sha256": "41ac…",
      "cardCount": 13156,
      "packSchema": 1,
      "installedBytes": 9876543
    }
  }
}
```

| field | type | meaning |
|---|---|---|
| `schema` | int | manifest schema version (`1`) |
| `version` | string | build version, the UTC build date `YYYY-MM-DD` — what the app compares to decide « mise à jour disponible » |
| `generatedAt` | string | UTC ISO-8601 build timestamp |
| `packs` | object | key = language code (`fr`, `ja`), value = pack entry |
| `packs[].file` | string | release asset file name, resolved against the same `releases/latest/download/` base |
| `packs[].bytes` | int | size of the `.gz` asset — shown to the user **before** any download (FR-23) and used for the free-space check |
| `packs[].sha256` | string | hex sha256 of the `.gz` asset; a mismatch deletes the download and keeps the old pack (EC-2) |
| `packs[].cardCount` | int | number of cards in the pack, also used by the 2 % guard (§7) |
| `packs[].packSchema` | int | `pack_meta.pack_schema` of the contained db; a pack whose value exceeds `AppConfig.PACK_SCHEMA` is refused (EC-17) |
| `packs[].installedBytes` | int | size of the **decompressed** `pack.db`; free space must cover `bytes + installedBytes` (EC-3) |

Install sequence on the device (`PackInstaller`): free-space check → resumable download
(`Range: bytes=<existing>-`, `.part` file) → sha256 verify → gunzip to `packs/<lang>/pack.db.tmp` →
open read-only and check `pack_meta.pack_schema` → `renameTo` the final path. The `renameTo` is the
**only** write to `packs/<lang>/pack.db`, so an interrupted install never leaves a partial pack and
never touches the collection (FR-23).

---

## 7. Release gate: the 2 % guard

`pipeline/tcgscan_pipeline/guard.py` compares the newly built manifest with the previously published
one (fetched from the release URL above; a 404 is tolerated on the very first run) and **fails**
when:

- any language's `cardCount` is lower than `98 %` of its previous `cardCount` — i.e. a loss of more
  than **2 %** of the cards;
- a language present in the previous manifest is missing from the new one;
- any crawl raised `TcgdexUnavailable`.

The gate is **all-or-nothing** (FR-22): either every pack *and* `manifest.json` are emitted into
`dist/`, or `dist/` is left empty and the process exits non-zero. The publishing step of
`.github/workflows/packs.yml` is guarded by `if: success()`, so a guard failure publishes nothing
and the previous release stays in place.
