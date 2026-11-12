#!/usr/bin/env bash
# Hook PostToolUse (Edit|Write) : réindexe les .md de docs après modification.
# Périmètre : voir CLAUDE.md, section "Index des documents .md".
# Dépendance : jq.

# Dossier des docs, relatif à la racine du projet ('.' = la racine elle-même).
DOCS_ROOT="docs"

# .md à ne jamais indexer, en plus des fichiers fixes ci-dessous.
# Chemins relatifs à la racine du projet (motifs `case`), par exemple "src/*" ou "backend/*".
EXCLUDE_GLOBS=(
  "docs/pack-format.md"
  "docs/models.md"
  "docs/benchmark.md"
  "docs/manual-debug.md"
  "docs/plans/*"
)

root=${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}
docs="$root"
[[ $DOCS_ROOT != "." ]] && docs="$root/${DOCS_ROOT#./}"

f=$(jq -r '.tool_response.filePath // .tool_input.file_path // empty')

[[ $f == *.md && $f == "$docs"/* && -f $f ]] || exit 0

# Fichiers jamais indexés (CLAUDE.md, INDEX.md, conventions-docs.md, README.md).
case ${f#"$docs"/} in
  CLAUDE.md|INDEX.md|conventions-docs.md|README.md|*/README.md) exit 0 ;;
esac
rel=${f#"$root"/}
for g in "${EXCLUDE_GLOBS[@]}"; do
  # shellcheck disable=SC2254
  case $rel in $g) exit 0 ;; esac
done

[[ -f $docs/reindex.sh ]] || exit 0

# Réindexe si le fichier a déjà un index (entier ou ancien format) ou fait 80 lignes ou plus.
first=$(head -n1 "$f")
if [[ $first =~ ^[0-9]+$ || $first == "INDEX : lignes"* ]] || (( $(wc -l < "$f") >= 80 )); then
  bash "$docs/reindex.sh" "$f"
fi
