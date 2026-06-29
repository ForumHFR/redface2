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
  # DÉLIMITATION (correctif audit #603) : la version doit être bordée par un caractère NON [0-9.]
  # de part et d'autre, sinon « 0.17.3 » passerait à tort contre « v0.17.30 — dev. » (fail-open).
  # La 1re ligne a la forme « Redface 2 v0.17.30 — dev. » : version toujours bordée (v… et espace).
  case "$first_line" in
    *[!0-9.]"$version"[!0-9.]*) : ;;
    *)
      echo "::error::$file : 1re ligne ne contient pas le versionName '$version' (freshness)"
      exit 1
      ;;
  esac
  # Compter les CARACTÈRES VISIBLES : `cat` via substitution retire le newline final (que Play ne
  # compte pas) ; sans ce strip, wc -m comptait le \n terminal et ramenait la limite réelle à 499.
  content="$(cat "$file")"
  chars="$(printf '%s' "$content" | LC_ALL=C.UTF-8 wc -m | tr -d '[:space:]')"
  (( chars <= 500 )) || {
    echo "::error::$file : $chars caractères (> 500, limite Play Console)"
    exit 1
  }
  echo "whatsnew OK : $(basename "$file") (${chars} car.)"
done
