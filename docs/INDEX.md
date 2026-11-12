# INDEX.md — Table of contents of the TCG Scan documentation

Naming and layout rules: [conventions-docs.md](conventions-docs.md) (imported into CLAUDE.md).

Docs of 80 lines or more carry their own index at the top (line 1 = last line of the index). To read:
- `docs/mdx file.md`: shows the file's index (sections and line ranges).
- `docs/mdx file.md A B`: shows lines A to B.

These commands run from the project root.

Ranges are deliberately not copied here: they went stale. Each file's index is regenerated automatically (hook `.claude/hooks/reindex-md.sh`, or `bash docs/reindex.sh` by hand).

<!-- One line per doc: link + one sentence. Project `vue-ensemble.md` sheets are not listed here but in the repository map. Update on every new doc, rename or move. -->

## Root (rules and tools)
- [conventions-docs.md](conventions-docs.md) — naming, layout and linking convention for the docs, project sheet template.
- [templates/](templates/) — task templates (`GOAL`, `JOURNAL`, `HANDOFF`, `DOC_CURRENT_TASK`, `PLAN`), task-start prompt (`task-prompt.md`) and project sheet (`vue-ensemble-projet.md`).
- `mdx` — reads an indexed `.md` (command: `docs/mdx`).
- `reindex.sh` — (re)generates the index of a `.md` (command: `bash docs/reindex.sh`).

## Current task (`current-task/`)
- `GOAL.md`, `JOURNAL.md`, `HANDOFF.md`, `DOC_CURRENT_TASK.md`, `PLAN.md` (optional) — see CLAUDE.md, section "In an ongoing task".

## Public docs (`docs/`, linked from the README, never indexed by the hook)
- [pack-format.md](pack-format.md) — pack and manifest format, embedding contract, 2 % release guard.
- [models.md](models.md) — shipped ML models: sources, sizes, sha256, licences.
- [benchmark.md](benchmark.md) — measured accuracy and latency.
- [plans/tcg-scan-v1.md](plans/tcg-scan-v1.md) — v1 implementation plan (tasks T1 to T31) from the previous workflow; task statuses are stale.
- `../SPEC.md` (repository root) — v1 specification.

## Shared docs (`project-specific-docs/`)
<!-- guide-*.md shared by all repositories. -->

## Repository `tgc-scan-app`
- [project-specific-docs/tgc-scan-app/vue-ensemble.md](project-specific-docs/tgc-scan-app/vue-ensemble.md) — repository map: projects `app` and `pipeline` (links to their sheets), folders without code, dependency diagram, incomplete pack publication chain.

## Finished tasks (`old-tasks/`)
<!-- One line per archived folder. -->
- [old-tasks/bootstrap-phase-5/](old-tasks/bootstrap-phase-5/) — project docs initialisation (GOAL and PLAN of BOOTSTRAP phase 5).
