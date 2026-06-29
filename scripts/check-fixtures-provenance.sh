#!/usr/bin/env bash
# Garde de provenance des fixtures de test (cf. AGENTS.md § Cadence — [enforced]).
# Toute fixture HTML/JSON sous */src/test/resources/fixtures/ doit avoir :
#   - un sidecar `<fixture>.source.txt` OU `<fixture sans ext>.source.txt`, OU
#   - un commentaire de provenance en tête (HTML `<!--` avec Source/Provenance/Captur…), OU
#   - une entrée explicite dans config/fixtures-provenance-allowlist.txt (legacy, backfill suivi).
# La charte interdit de fabriquer une provenance : les fixtures legacy non sourçables sont
# allow-listées et suivies par une issue de backfill, jamais inventées.
set -euo pipefail

allowlist="config/fixtures-provenance-allowlist.txt"
missing=0

# NB : pas de `grep -q` / `grep -Fxq` en aval d'un pipe ici. Sous `set -o pipefail`, le `-q`
# sort au premier match et ferme le pipe → SIGPIPE (exit 141) sur la commande amont, que pipefail
# propage comme échec → faux négatif intermittent. On retire le `-q` (grep consomme alors toute son
# entrée, l'amont finit proprement) et on jette la sortie via `>/dev/null`.
is_allowed() {
  local path="$1"
  [[ -f "$allowlist" ]] &&
    grep -vE '^[[:space:]]*(#|$)' "$allowlist" | grep -Fx -- "$path" >/dev/null
}

has_header_provenance() {
  local file="$1"
  [[ "$(head -c 4 "$file")" == "<!--" ]] &&
    head -n 40 "$file" | grep -Ei 'Source|Provenance|Captured|Captur|Origine' >/dev/null
}

while IFS= read -r -d '' fixture; do
  rel="${fixture#./}"
  sidecar_without_ext="${fixture%.*}.source.txt"
  sidecar_with_ext="${fixture}.source.txt"

  if [[ -f "$sidecar_without_ext" || -f "$sidecar_with_ext" ]]; then
    continue
  fi
  if has_header_provenance "$fixture"; then
    continue
  fi
  if is_allowed "$rel"; then
    continue
  fi

  echo "::error file=$rel::Fixture sans provenance (sidecar .source.txt / header / allow-list)"
  missing=1
done < <(
  # Scan global (tous modules), en élaguant les dossiers lourds non versionnés.
  find . -type d \( -name .git -o -name build -o -name '.gradle*' -o -name '.kotlin' -o -name node_modules \) -prune -o \
    -path '*/src/test/resources/fixtures/*' -type f \( -name '*.html' -o -name '*.json' \) -print0
)

if [[ "$missing" -eq 0 ]]; then
  echo "Provenance fixtures : OK"
fi
exit "$missing"
