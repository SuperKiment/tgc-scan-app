# PLAN — Project docs initialisation

<!-- Written by the orchestrator only, for a large task (see CLAUDE.md, section Workflow). The project lead validates the plan, then each step. -->

The project lead asked on 2026-11-12 to run the whole phase without stopping between steps ("fais tout jusqu'à ce que ça soit bon"); validation happens once, at the end.

## Step 1 — Skeleton
- Create `project-specific-docs/tgc-scan-app/app/` and `project-specific-docs/tgc-scan-app/pipeline/`.
- Orchestrator checks: folder names match the repository folders exactly.

## Step 2 — Project sheets
- Two `sonnet` research agents (medium effort), in parallel: `app/vue-ensemble.md` and `pipeline/vue-ensemble.md` from `templates/vue-ensemble-projet.md`.
- Orchestrator checks: template sections and order, claims spot-checked in the code, inferences marked "(not verified)".

## Step 3 — Repository map
- `project-specific-docs/tgc-scan-app/vue-ensemble.md`: project table, lines for `tools/`, `gradle/`, `docs/`, mermaid diagram of real dependencies (`visual-docs` skill).
- Orchestrator checks: each dependency in the diagram traced to a file.

## Step 4 — Background docs
- None written now: conventions need a validation per convention. Candidates listed in HANDOFF.

## Step 5 — Final index and links
- `INDEX.md` lists the repository map; relative links resolved by script; indexes present on `.md` of 80+ lines.
- Orchestrator checks: link script output, `docs/mdx` on long files.
