#!/usr/bin/env bash
# Rapport NON bloquant des fichiers de production Kotlin dépassant un seuil de lignes
# (cf. AGENTS.md § Cadence — [advisory]). Écrit dans $GITHUB_STEP_SUMMARY, exit 0 toujours.
set -euo pipefail
threshold="${1:-800}"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
{
  echo "## Fichiers de production Kotlin > ${threshold} lignes"
  echo
  found=0
  while IFS= read -r -d '' f; do
    n=$(wc -l < "$f")
    if [ "$n" -gt "$threshold" ]; then
      echo "- \`${f#./}\` — ${n} lignes"
      found=1
    fi
  done < <(find app core feature build-logic -path '*/src/main/*' -name '*.kt' -type f -print0 2>/dev/null | sort -z)
  [ "$found" -eq 0 ] && echo "_Aucun fichier au-dessus du seuil._"
} >> "$out"
exit 0
