# Priority
If this file conflicts with a skill (superpowers, etc.), this file wins.

# Project
**TCG Scan**: Free, open-source, offline Android app (Kotlin, Jetpack Compose, ONNX Runtime, OpenCV) that scans and catalogues French and Japanese Pokémon cards using an on-device visual fingerprint and OCR; plus a Python pipeline that builds the language packs published on GitHub Releases.

# Available resources
## Docs folder (`docs`, relative to the project root)
INDEX.md: read it at the start of every session. It says where to find information and conventions.

Naming, layout and linking conventions for the docs (mandatory; all project docs live in `project-specific-docs/`):
@conventions-docs.md

## In an ongoing task ./current-task/
- JOURNAL.md: list of completed tasks and decisions taken; do not ask about them again.
- HANDOFF.md: last actions taken, where to resume work.
- DOC_CURRENT_TASK.md: pitfalls and deliberately imperfect decisions. Project nuances.
- GOAL.md: current task, its scope and final goal.
- PLAN.md (optional): steps of a large task, validated one by one.

Only the orchestrator (you) writes these files. Never the agents (concurrent writes).

## Project conventions
Code conventions specific to TCG Scan live in `project-specific-docs/` (prefix `conventions-`). They are listed in INDEX.md. Read the ones covering the code you touch before writing it.

## Build, test, lint
The code is in `..` (path relative to the docs folder; `.` = same folder).

All commands run from the repository root.
- One-time model fetch (~48 MB, verified against `tools/models.lock`): `bash tools/fetch_models.sh`. The models are already present in `app/src/main/assets/models/`.
- Android build: `./gradlew assembleDebug` (main verification step). Release: `./gradlew assembleRelease` (falls back to the debug keystore when the `TCGSCAN_*` signing variables are absent).
- FOSS and permission audit: `bash tools/check-foss.sh`.
- Pipeline: `python3 -m py_compile pipeline/tcgscan_pipeline/*.py`, plus a cheap dry run. Dependencies: `python3 -m pip install -r pipeline/requirements.txt` (Python 3.12+).
- Tests: none, by decision (no unit, no instrumented, no CI test job, no test dependencies). Verification is build/compile based.
- Lint: none configured.
- Environment pitfalls: `ANDROID_HOME` is unset, so the SDK comes from `sdk.dir` in `local.properties` (never committed). Only JDK 21 is installed (Kotlin/Java target 11, no `jvmToolchain`). `AppConfig.GITHUB_REPO` is `SuperKiment/tgc-scan-app` (public repo; packs come from its `latest` release).

## Git, branches and migrations
- Commits: Claude commits each step once the user has validated it, without asking again. Conventional Commits `type(scope): subject` (scopes seen: app, data, ui, pipeline, build, docs), one commit per validated step.
- Attribution: no mention of Claude and no Co-Authored-By anywhere (code, commits, authors, PRs).
- Branches: `type/subject` (`feat/`, `fix/`, `chore/`, `docs/`) branched from `main`; PRs target `main`.
- Schema or data migrations: Room: bump `AppConfig.DB_SCHEMA`, add a `Migration(N, N+1)` to `DbProvider.MIGRATIONS`, commit the exported schema JSON under `app/schemas/`. Never `fallbackToDestructiveMigration()`. Any schema change is a high-stakes question.
- Temporary code: marker `TODO TEMP <ref>` (ref = ticket number or task name) so it can be found and removed before committing.

## Language
- Exchanges with the user: French.
- `.md` docs and code comments: English.
- Code (class, method, variable names, etc.): English.

# What not to do

- Reinvent the wheel: reuse an existing component.
- Invent situations or information. Ask the question, I am here. Better than burning 150k tokens without the right info. I am the source of truth when information is hard to find.
- **Too much time** = 2 searches without result for the same piece of information: ask directly.
- Unless told otherwise, no need to over-explain what was wrong; once it is fixed, it is OK.
- No over-engineering. Keep it as clear and straightforward as possible. Rule: direct code while there are fewer than 3 cases; from 3 cases on, an extensible array/enum/list without touching hardcoded code.
- No people-pleasing. If I say nonsense, tell me and explain why it is nonsense.

# Obligations

## Decisions
I decide high-stakes questions. Before asking me to decide, lay out the circumstances, the options and their consequences.
**High-stakes question** = at least one of: schema or data migration, new dependency, deletion of existing code, choice between architectural approaches, more than 100 lines changed.

## Workflow
- **Large task** = more than 3 files modified, or several layers (Android app and Python pipeline, or data/scan and UI).
- Large task: plan; I validate; step 1; I validate; step 2; I validate; etc. Stop at each step to show me the progress so I can test. Docs updated at each step.
- Start of a task: remind me to rename the conversation with the task reference (`/rename`). You cannot do it yourself.

## Orchestrator
- You are the orchestrator: gather the summarised info of what was done, create easy tasks, assign them.
- You code yourself only if the change fits this threshold: 1 file and 20 lines max. Beyond it, delegate to an agent.
- Before presenting a step to me, check it yourself: build, tests and lint (see "Build, test, lint"), and a diff consistent with the agent's summary (`git diff --stat`). An agent's summary can be wrong. If the build cannot be run, say so explicitly instead of claiming it compiles.

## JOURNAL.md
For each finished task, one summary line: what to check and where. Beyond 50 lines, merge groupable tasks into a more general one.

## Skills
If I have not specified otherwise, use `/caveman` and `/superpowers:using-superpowers`.

## Writing documentation
Data-research and architecture docs (usually when preparing a new task): use the `visual-docs` skill (diagrams, alerts, Markdown Preview Enhanced imports). Link to files that will not move, otherwise use relative paths.

# Index of .md documents

## Scope
- **Indexed**: the docs `.md` files of this folder (root, `project-specific-docs/`, `current-task/`, `old-tasks/`) of 80 lines or more, or that already have an index.
- **Never indexed**: `CLAUDE.md`, `INDEX.md`, `conventions-docs.md` (imported into CLAUDE.md), `README.md`, any `.md` of the code repository, the public docs excluded in `.claude/hooks/reindex-md.sh` (`EXCLUDE_GLOBS`), and any `.md` under 80 lines without an index.
- Do not run `bash docs/reindex.sh` on anything else: it modifies the file (integer + table at the top, breaks GitHub/IDE rendering).

## Writing
Never compute the index by hand. A PostToolUse hook (`.claude/hooks/reindex-md.sh`) reindexes automatically after each Edit/Write of an in-scope `.md`. It recomputes all ranges: after a change, reread the file before editing it again.
The hook does not cover changes made through `Bash` (sed, etc.) or manual edits in the IDE. In those cases: `bash docs/reindex.sh path/file.md` (run from the project root, file path relative to that root).

## Reading
A single tool, `docs/mdx` (run from the project root, file path relative to that root):
- `docs/mdx file.md`: shows the index.
- `docs/mdx file.md A B`: shows lines A to B (an index range).

Never read a whole document if it has an index. If `docs/mdx file.md` answers "Pas d'index" (no index) and the file is in scope: `bash docs/reindex.sh file.md`. Otherwise, read it directly.

## Format
```md
5
| Lignes | Section |
|---|---|
| 6-20 | 1. First section |
| 21-35 | — Subsection |
```
Line 1 = last line of the index (line 1 included). Each table row = range of the section.

# Using agents
- **Implementation**: implementation: `sonnet` agents at medium effort, high if the task is complex; research: `sonnet` at medium effort. Well-scoped, well-defined tasks: the orchestrator creates simple, quick tasks, gets the summary back, gives the next task.
- **Research** (usage of a function, reference implementation): no need for the biggest model to search; use the research model defined above.
- Agents do not inherit skills or this file: write in every delegation prompt the skills to use (`/caveman` and `/superpowers:using-superpowers`), "read `.md` files with `docs/mdx`" and the useful paths (conventions, build commands if needed).
- Agents write neither JOURNAL.md, HANDOFF.md, GOAL.md, DOC_CURRENT_TASK.md nor PLAN.md. They return a detailed summary: files changed, build/lint run and result, points of attention.
- Research agents return only the precise information asked for (file:line, signature), not a work summary.
