# Benchmark — recognition accuracy and latency

**Status: to be filled manually.** This project ships no automated test suite, so the accuracy and
latency requirements (NFR-6, NFR-7) and the acceptance criteria that need a physical lot (AC-8,
AC-9, AC-10) are verified in a manual session on the reference device and recorded here.

Do not edit the thresholds: they come from the SPEC.

## Reference device

| field | value |
|---|---|
| model | _to fill_ |
| SoC | _to fill_ (target class: Snapdragon 6 or equivalent) |
| RAM | _to fill_ (target: 6 GB) |
| Android version / API | _to fill_ (minSdk is 26) |
| APK ABI | _to fill_ (`arm64-v8a` or `armeabi-v7a`) |
| app version / commit | _to fill_ |
| packs installed (lang, version, card count) | _to fill_ |

## Lot composition

| field | value |
|---|---|
| FR cards in the lot | _to fill_ (target: 200, mixed eras, holos and sleeved cards — AC-8, EC-8) |
| JP cards in the lot | _to fill_ (target: 100 — AC-9) |
| of which JP cards with a visual reference (`has_embed = 1`) | _to fill_ |
| lighting conditions | _to fill_ |
| date of the session | _to fill_ |

## Accuracy

| metric | requirement | measured |
|---|---|---|
| FR cards with the correct card in first position | ≥ 190 / 200 (95 %) — AC-8, NFR-7 | _to fill_ |
| JP cards *with a visual reference* with the correct card in first position | ≥ 80 % — AC-9, NFR-7 | _to fill_ |
| cards pre-checked with a **wrong** identification | ≤ 2 % — AC-10 | _to fill_ |
| cards sent to « à vérifier » | informative | _to fill_ |
| cards with no detection at all | informative | _to fill_ |

## Latency (stabilised card → result)

| metric | requirement | measured |
|---|---|---|
| P50 | informative | _to fill_ |
| P90 | < 2 s — AC-10, NFR-6 | _to fill_ |
| P99 | informative | _to fill_ |
| mean, per stage (detect / warp / embed / OCR / match) | informative | _to fill_ |

## Observed failure modes

| case | cards affected | note |
|---|---|---|
| holo reflection | _to fill_ | EC-7 |
| sleeved card | _to fill_ | EC-8 |
| identical-illustration reprint, unreadable number | _to fill_ | EC-11 |
| card with no visual reference (OCR-only) | _to fill_ | FR-3 |
| other | _to fill_ | |

## Tuning used

Record the values of `app/src/main/java/org/tcgscan/app/scan/ScanTuning.kt` that produced the
numbers above, so a later change can be compared against them.

| constant | value at measurement time |
|---|---|
| visual accept cosine | _to fill_ (plan default 0.72) |
| runner-up margin | _to fill_ (plan default 0.06) |
| top-K | _to fill_ (plan default 30) |
| candidates kept | _to fill_ (plan default 3) |
| stabilisation frames | _to fill_ (plan default 3) |

The ordered manual procedure, including the `adb` commands, is in `docs/manual-debug.md`.
