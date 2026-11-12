# GOAL — Project docs initialisation

Reference: bootstrap-phase-5. The claude-kit workflow is installed (phases 0 to 4); this task gives the repository its first project docs.

## Scope
- `project-specific-docs/tgc-scan-app/`: repository map (`vue-ensemble.md`) with a mermaid dependency diagram.
- One `vue-ensemble.md` sheet per first-level project: `app/`, `pipeline/`.
- Folders without code (`tools/`, `gradle/`, `docs/`): one line in the repository map.
- `INDEX.md` updated, relative links checked.

## Out of scope (unless decided otherwise)
- Code changes, reconciliation of `docs/plans/tcg-scan-v1.md` with the uncommitted work (next task).
- `conventions-*.md`: each convention needs the project lead's validation before it is written; none is written in this task.
- Public docs (`pack-format.md`, `models.md`, `benchmark.md`, `plans/`, `SPEC.md`): untouched.

## Final goal
Each first-level folder of the repository has a sheet or a line in the repository map; all relative links between docs resolve; `.md` files of 80 lines or more carry their index.
