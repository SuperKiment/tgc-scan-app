"""TCG Scan pack pipeline.

Crawls the TCGdex API, embeds the card visuals and builds one gzipped SQLite pack per language
plus ``manifest.json``, exactly as specified in ``docs/pack-format.md`` (authoritative).

The package is deliberately import-light: submodules pull in their own heavy dependencies
(``numpy``, ``onnxruntime``, ``cv2``) so that ``python -m tcgscan_pipeline.tcgdex`` only needs
``requests``.
"""
