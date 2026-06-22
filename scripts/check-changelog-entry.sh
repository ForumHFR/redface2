#!/usr/bin/env bash
# Garde release [enforced] : app/CHANGELOG.md (canonique applicatif) doit contenir un heading
# pour le versionName donné. Fail-closed. Appelé par la CI (au bump de versionName) et par
# release.yml (assert final avant build/tag/upload). Aucun secret, aucun Gradle.
set -euo pipefail
version="${1:?usage: check-changelog-entry.sh <versionName>}"
changelog="app/CHANGELOG.md"
[[ -f "$changelog" ]] || { echo "::error::$changelog introuvable"; exit 1; }
# Match en deux temps (pas de regex sur la version → aucun souci d'échappement de métacaractères) :
# 1) isoler les lignes de heading markdown, 2) y chercher la version en chaîne LITTÉRALE (grep -F).
if grep -E '^#{1,6}[[:space:]]' "$changelog" | grep -Fq -- "$version"; then
  echo "CHANGELOG: entrée trouvée pour $version"
else
  echo "::error::Aucun heading de changelog pour la version '$version' dans $changelog (bump versionName sans entrée CHANGELOG ?)"
  exit 1
fi
