# Documentation conventions

Mandatory rules to create, name, file and link the docs of this folder. They apply to Claude and to agents. They do not apply to the code's `.md` files (in `..`, path relative to this docs folder), nor to the public docs already in this folder (`pack-format.md`, `models.md`, `benchmark.md`, `plans/`), which are linked from the README and keep their names.

## Layout

```
docs/
├─ CLAUDE.md, INDEX.md, conventions-docs.md   Claude/agent rules, table of contents, this convention
├─ mdx, reindex.sh                            reading and indexing tools
├─ templates/                                 task, prompt and project sheet templates
├─ current-task/                              ongoing task (fixed files, see CLAUDE.md)
├─ old-tasks/<Task>/                          finished tasks (archives, never renamed)
└─ project-specific-docs/                     all docs specific to the project
   ├─ guide-*.md                              docs shared by all repositories (build, run)
   └─ <Repository>/                           one folder per repository (or first-level project)
      ├─ vue-ensemble.md                      map of the repository's projects
      ├─ conventions-*.md, domaine-*.md       docs spanning the repository
      └─ <Project>/                           one folder per first-level project of the repository
         ├─ vue-ensemble.md                   project sheet (mandatory)
         └─ carto-*.md, domaine-*.md, guide-*.md
```

<!-- `docs` is the docs folder, relative to the project root (`.` = the root itself). The inner layout is the same whether the docs are at the root or in a subfolder (e.g. `docs`), and whether they are versioned with the code or not. -->

- A doc specific to one project goes in that project's folder. A doc covering several projects of the same repository goes at the repository root. A doc shared by all repositories goes at the root of `project-specific-docs/`.
- With a single repository, `project-specific-docs/<Repository>/` is still useful: it keeps the structure ready if a second repository arrives.
- Subprojects and test projects: documented in the folder of their first-level parent project.
- Folders without code (pipelines, request collections, legacy binaries…): one line in the repository's `vue-ensemble.md`, no folder.

## Naming

- Files: lowercase, ASCII without accents, words separated by hyphens (kebab-case), `.md` extension. Language of the words: English.
- Project folders: exact name of the folder in the repository, case included.
- Mandatory prefix by doc type:

| Prefix / name | Content | Example |
|---|---|---|
| `vue-ensemble.md` | Entry point of a folder: project sheet or repository map | `<Repository>/<Project>/vue-ensemble.md` |
| `carto-<subject>.md` | Structure of a project: folders, layers, who calls what | `<Repository>/<Project>/carto-structure.md` |
| `conventions-<subject>.md` | Code rules to apply | `<Repository>/conventions-naming.md` |
| `domaine-<subject>.md` | Business flow, often spanning several projects | `<Repository>/domaine-scan.md` |
| `guide-<subject>.md` | Procedure, commands, how-to | `project-specific-docs/guide-build-run.md` |

- Naming exceptions: `CLAUDE.md`, `INDEX.md`, the public docs listed above, and the uppercase task files (`GOAL.md`, `JOURNAL.md`, `HANDOFF.md`, `DOC_CURRENT_TASK.md`, `PLAN.md`, and uppercase task appendices).
- An analysis doc produced during a task stays in `current-task/`. If it remains useful after the task, move it to `project-specific-docs/` with a compliant name.

## Links and references

- Relative Markdown links to other docs: `[project map](../Project/carto-structure.md)`. No absolute paths.
- To cite a section, name it after the link: `[conventions](conventions-naming.md), section "Class naming"`. No line numbers (indexes recompute them).
- Repository code: path from the repository root, in code format (for example `app/src/main/java/org/tcgscan/app/AppConfig.kt`).
- `INDEX.md` lists all docs, except project `vue-ensemble.md` sheets: those are listed by the repository map (`<Repository>/vue-ensemble.md`).
- Every new doc, rename or move updates `INDEX.md` (or the repository map for a sheet) and the links pointing to it (`grep -rn "<old-name>" --include=*.md`, excluding `old-tasks/`).

## Template of a project `vue-ensemble.md` sheet

Full template: `templates/vue-ensemble-projet.md` (docs `templates/` folder). Expected content:

```md
# <Project>

> Repository `<Repository>`, path `<Repository>/<Project>/`. Checked on <YYYY-MM-DD> from <sources read>.

## What it is for
## Tech
Type (app, API, lib, service, console, pipeline…), target framework, build file.
## Dependencies
References between projects, who calls it, what it calls (database, API, services).
## Place in the whole
## Tests
## Related docs
```

- Write only what is verified in the code. Mark an inference with "(not verified)". A section without info: "Nothing found".
- Writing architecture docs: `visual-docs` skill (mermaid diagrams, alerts).
