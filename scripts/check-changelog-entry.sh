#!/usr/bin/env bash
# Garde release [enforced] : app/CHANGELOG.md (canonique applicatif) doit contenir un heading
# pour le versionName donné. Fail-closed. Appelé par la CI (au bump de versionName) et par
# release.yml (assert final avant build/tag/upload). Aucun secret, aucun Gradle.
set -euo pipefail
version="${1:?usage: check-changelog-entry.sh <versionName>}"
changelog="app/CHANGELOG.md"
[[ -f "$changelog" ]] || { echo "::error::$changelog introuvable"; exit 1; }
# Match en deux temps (pas de regex sur la version → aucun souci d'échappement de métacaractères) :
# 1) isoler les lignes de heading markdown, 2) y chercher la version en token DÉLIMITÉ.
# NB : surtout PAS de pipe « grep | grep -Fq » ici. Sous `set -o pipefail`, le `-q` sort au
# premier match et ferme le pipe ; le grep amont reçoit alors un SIGPIPE (exit 141) que pipefail
# propage comme échec du pipeline → faux négatif intermittent selon le timing du runner
# (« grep: write error: Broken pipe »). On capture d'abord les headings, puis on teste via `case`,
# sans pipe ni sous-processus à fermeture anticipée.
# DÉLIMITATION (correctif audit #603) : la version doit être bordée par un caractère NON [0-9.]
# de part et d'autre, sinon « 0.17.3 » passerait à tort contre le heading « 0.17.30 » (fail-open).
# Les headings ont la forme « ## `0.17.30` — … » donc la version est toujours bordée par un
# backtick ; le motif générique [!0-9.] couvre aussi tout futur format sans dépendre du backtick.
headings="$(grep -E '^#{1,6}[[:space:]]' "$changelog" || true)"
case "$headings" in
  *[!0-9.]"$version"[!0-9.]*)
    echo "CHANGELOG: entrée trouvée pour $version"
    ;;
  *)
    echo "::error::Aucun heading de changelog pour la version '$version' dans $changelog (bump versionName sans entrée CHANGELOG ?)"
    exit 1
    ;;
esac
