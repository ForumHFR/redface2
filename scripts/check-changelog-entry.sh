#!/usr/bin/env bash
# Garde release [enforced] : app/CHANGELOG.md (canonique applicatif) doit contenir un heading
# pour le versionName donné. Fail-closed. Appelé par la CI (au bump de versionName) et par
# release.yml (assert final avant build/tag/upload). Aucun secret, aucun Gradle.
set -euo pipefail
version="${1:?usage: check-changelog-entry.sh <versionName>}"
changelog="app/CHANGELOG.md"
[[ -f "$changelog" ]] || { echo "::error::$changelog introuvable"; exit 1; }
# Match en deux temps (pas de regex sur la version → aucun souci d'échappement de métacaractères) :
# 1) isoler les lignes de heading markdown, 2) y chercher la version en chaîne LITTÉRALE.
# NB : surtout PAS de pipe « grep | grep -Fq » ici. Sous `set -o pipefail`, le `-q` sort au
# premier match et ferme le pipe ; le grep amont reçoit alors un SIGPIPE (exit 141) que pipefail
# propage comme échec du pipeline → faux négatif intermittent selon le timing du runner
# (« grep: write error: Broken pipe »). On capture d'abord les headings, puis on teste en
# substring littérale via `case`, sans pipe ni sous-processus à fermeture anticipée.
headings="$(grep -E '^#{1,6}[[:space:]]' "$changelog" || true)"
case "$headings" in
  *"$version"*)
    echo "CHANGELOG: entrée trouvée pour $version"
    ;;
  *)
    echo "::error::Aucun heading de changelog pour la version '$version' dans $changelog (bump versionName sans entrée CHANGELOG ?)"
    exit 1
    ;;
esac
