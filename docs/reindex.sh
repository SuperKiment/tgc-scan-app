#!/usr/bin/env bash
# Usage: reindex.sh fichier.md [...]
# (Re)génère l'index en tête de fichier. Idempotent.
# Format : ligne 1 = N (dernière ligne de l'index), puis tableau | Lignes | Section |.
# Migre aussi l'ancien format "INDEX : lignes 0-5".
set -euo pipefail

for f in "$@"; do
  first=$(head -n1 "$f")
  skip=0
  [[ $first =~ ^[0-9]+$ ]] && skip=$first
  [[ $first =~ ^INDEX\ :\ lignes\ [0-9]+-([0-9]+) ]] && skip=${BASH_REMATCH[1]}

  tail -n +$((skip + 1)) "$f" | awk '
    { b[NR] = $0 }
    /^```/ { fence = !fence; next }
    !fence && match($0, /^#+ /) { n++; ln[n] = NR; lv[n] = RLENGTH - 1; t[n] = substr($0, RLENGTH + 1) }
    END {
      for (i = 1; i <= n; i++) if (lv[i] == 1) h1++
      s = (h1 == 1 && lv[1] == 1 && n > 1) ? 2 : 1   # titre H1 unique : non indexé
      min = 9
      for (i = s; i <= n; i++) if (lv[i] < min) min = lv[i]
      N = 3 + (n - s + 1)
      print N
      print "| Lignes | Section |"
      print "|---|---|"
      for (i = s; i <= n; i++) {
        end = (i < n ? ln[i + 1] - 1 : NR)
        printf "| %d-%d | %s%s |\n", ln[i] + N, end + N, (lv[i] > min ? "— " : ""), t[i]
      }
      for (i = 1; i <= NR; i++) print b[i]
    }' > "$f.tmp"
  mv "$f.tmp" "$f"
done
