#!/usr/bin/env bash
# Garde release [enforced] : chaque fichier whatsnew-* a sa PREMIÈRE LIGNE contenant le versionName
# courant (freshness) ET fait ≤ 500 CARACTÈRES (limite Play Console). Fail-closed.
# Appelé par release.yml avant build/tag/upload. Aucun secret, aucun Gradle.
set -euo pipefail
version="${1:?usage: check-whatsnew.sh <versionName>}"
dir="app/src/main/play/whatsnew"
shopt -s nullglob
files=("$dir"/whatsnew-*)
(( ${#files[@]} > 0 )) || { echo "::error::aucun fichier whatsnew dans $dir"; exit 1; }
for file in "${files[@]}"; do
  first_line="$(head -n 1 "$file")"
  [[ "$first_line" == *"$version"* ]] || {
    echo "::error::$file : 1re ligne ne contient pas le versionName '$version' (freshness)"
    exit 1
  }
  chars="$(LC_ALL=C.UTF-8 wc -m < "$file" | tr -d '[:space:]')"
  (( chars <= 500 )) || {
    echo "::error::$file : $chars caractères (> 500, limite Play Console)"
    exit 1
  }
  echo "whatsnew OK : $(basename "$file") (${chars} car.)"
done
