#!/usr/bin/env bash
# Garde parité de lecture [enforced] — garde A « couplage par chemins » (#1045). Une PR qui touche
# la surface de lecture (feature/topic|messages src/main, core/ui post|list|pager) doit toucher
# docs/specs/reading-parity.md, OU porter dans son corps une ligne en début de ligne :
#   Parity-Impact: none — <justification d'au moins 20 caractères>
# Le corps est relu EN DIRECT (gh pr view), jamais depuis le payload figé de l'événement : éditer le
# corps puis RELANCER le job repo-guards suffit à débloquer, sans nouveau commit. La garde ne relit
# le corps que si elle a déjà déclenché (aucun appel API sinon).
#
# La fonction réelle de la garde n'est pas de bloquer : c'est de poser la question de la parité au
# moment exact où elle doit l'être. D'où l'échappatoire en une phrase, pas en label (#1045 : « un
# label se pose machinalement, une phrase demande d'y avoir réfléchi »).
#
# Usage : check-reading-parity-touch.sh <base-ref>
#   <base-ref> : la base de la PR, au SHA FIGÉ de l'événement (github.event.pull_request.base.sha,
#   fetché explicitement par le step CI). PAS la pointe courante de la branche base : un re-run
#   conserve le merge commit initial (GitHub réutilise GITHUB_SHA/GITHUB_REF du run d'origine),
#   donc differ contre une base qui a avancé montrerait des commits étrangers à la PR.
#   HEAD est le merge ref de la PR, donc le diff deux-points EST le diff effectif de la PR.
# Env (tests et runs locaux) :
#   PARITY_CHANGED_FILES : liste de fichiers (un par ligne) — remplace le diff git.
#   PARITY_PR_BODY       : corps de PR — remplace l'appel gh.
#   PR_NUMBER / GH_REPO / GH_TOKEN : contexte gh pour la lecture en direct du corps.
#
# Mesurabilité (exigence #1045 : taux de déclenchement consigné, garde recalibrée ou retirée s'il
# dépasse nettement les estimations 25–65 %) : chaque run logge une ligne stable
# « parity-guard: verdict=<not-triggered|matrix-updated|escape-hatch|blocked> » (logs du job
# repo-guards + step summary). Bilan sur les PRs mergées, à consigner sur #1045 :
#   gh pr list -R ForumHFR/redface2 --state merged --limit 50 --json number,body,files --jq '
#     map({hit: ([.files[].path
#                 | select(test("^(feature/(topic|messages)/src/main/|core/ui/src/main/.*/(post|list|pager)/)"))]
#                | length > 0),
#          matrix: ([.files[].path | select(. == "docs/specs/reading-parity.md")] | length > 0),
#          escape: (.body // "" | test("(?m)^[ \\t]*Parity-Impact:[ \\t]*[Nn]one"))})
#     | {prs: length, triggered: map(select(.hit)) | length,
#        via_matrix: map(select(.hit and .matrix)) | length,
#        via_escape: map(select(.hit and .escape)) | length}'
#
# Aucun secret, aucun Gradle. Fail-closed sur la surface, fail-open hors surface.
set -euo pipefail

matrix_page="docs/specs/reading-parity.md"
min_justification_chars=20

if [[ -n "${PARITY_CHANGED_FILES+x}" ]]; then
  changed="$PARITY_CHANGED_FILES"
else
  base="${1:?usage: check-reading-parity-touch.sh <base-ref> (ou env PARITY_CHANGED_FILES)}"
  # --no-renames : un déplacement pur hors de la surface gardée doit exposer l'ANCIEN chemin
  # (delete+add), sinon la détection de rename de git ne liste que la destination et sortir un
  # fichier de la surface de lecture passerait sous le radar.
  changed="$(git diff --no-renames --name-only "$base" HEAD)"
fi

surface=()
matrix_touched=0
while IFS= read -r f; do
  [[ -n "$f" ]] || continue
  case "$f" in
    "$matrix_page") matrix_touched=1 ;;
    feature/topic/src/main/* | feature/messages/src/main/* | \
    core/ui/src/main/*/post/* | core/ui/src/main/*/list/* | core/ui/src/main/*/pager/*)
      surface+=("$f") ;;
  esac
done <<<"$changed"

emit_verdict() {
  local verdict="$1"
  echo "parity-guard: verdict=$verdict surface_files=${#surface[@]}"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "parity-guard: **$verdict** (${#surface[@]} fichier(s) de surface de lecture)" \
      >>"$GITHUB_STEP_SUMMARY"
  fi
}

if [[ ${#surface[@]} -eq 0 ]]; then
  echo "Aucun fichier de la surface de lecture touché -> garde parité non déclenchée."
  emit_verdict "not-triggered"
  exit 0
fi

echo "Surface de lecture touchée (${#surface[@]} fichier(s)) :"
printf '  - %s\n' "${surface[@]}"

if [[ "$matrix_touched" -eq 1 ]]; then
  echo "$matrix_page touché dans la même PR -> règle d'entretien satisfaite."
  emit_verdict "matrix-updated"
  exit 0
fi

# Échappatoire : lecture EN DIRECT du corps de la PR (pas le payload figé de l'événement).
if [[ -n "${PARITY_PR_BODY+x}" ]]; then
  body="$PARITY_PR_BODY"
elif [[ -n "${PR_NUMBER:-}" ]]; then
  body="$(gh pr view "$PR_NUMBER" --json body --jq .body)"
else
  body=""
fi

# Les commentaires HTML sont retirés AVANT l'extraction du marqueur : GitHub ne les rend pas,
# donc une justification qui y vivrait serait invisible au reviewer censé la juger — elle ne
# satisfait rien (gate #1045). Fail-closed : un commentaire non fermé rend invisible tout ce qui
# le suit, on retire donc tout jusqu'à la fin du corps.
strip_html_comments() {
  local s="$1" out=""
  while [[ "$s" == *'<!--'* ]]; do
    out+="${s%%<!--*}"
    s="${s#*<!--}"
    if [[ "$s" == *'-->'* ]]; then
      s="${s#*-->}"
    else
      s=""
    fi
  done
  printf '%s' "$out$s"
}
body="$(strip_html_comments "$body")"

# Extraction sans pipe fermant (cf. piège SIGPIPE documenté dans check-changelog-entry.sh) : sed
# capture la justification de chaque ligne marqueur, puis on boucle en bash. Le marqueur doit être
# en début de ligne — les mentions dans le template (backticks, guillemets, bullets) ne matchent
# pas. Le séparateur après « none » est OBLIGATOIRE ([-—–:]+) : « none » collé à du texte
# (noneXXXX) n'est pas un marqueur, c'est un accident.
justifications="$(sed -nE \
  's/^[[:space:]]*Parity-Impact:[[:space:]]*[Nn]one[[:space:]]*[-—–:]+[[:space:]]*(.*)$/\1/p' \
  <<<"$body" || true)"
escape_ok=0
escape_just=""
while IFS= read -r j; do
  # Trim des espaces de bord (dont le \r d'un corps CRLF) AVANT le comptage : une raison de 19
  # caractères plus un espace final ne fait pas 20 caractères.
  j="${j#"${j%%[![:space:]]*}"}"
  j="${j%"${j##*[![:space:]]}"}"
  [[ -n "$j" ]] || continue
  if [[ "${#j}" -ge "$min_justification_chars" ]]; then
    escape_ok=1
    escape_just="$j"
    break
  fi
done <<<"$justifications"

if [[ "$escape_ok" -eq 1 ]]; then
  echo "Échappatoire trouvée dans le corps de la PR : « Parity-Impact: none — $escape_just »"
  echo "::notice::Garde parité de lecture passée par échappatoire (Parity-Impact: none). Le" \
    "reviewer juge la justification : « $escape_just »"
  emit_verdict "escape-hatch"
  exit 0
fi

echo ""
echo "Garde parité de lecture (#1045) — cette PR touche la surface de lecture sans mettre à jour"
echo "la matrice de parité ($matrix_page)."
echo ""
echo "Deux façons de passer :"
echo "  1. Mettre à jour $matrix_page dans cette même PR : ajouter la ligne de la fonction"
echo "     touchée, ou basculer son verdict (oui livré / non par nature / oui mais absent)."
echo "  2. Pas d'impact sur la parité de lecture Topic<->MP ? Ajouter au CORPS de la PR, seule en"
echo "     début de ligne :"
echo "       Parity-Impact: none — <pourquoi la parité n'est pas affectée, 20 caractères minimum>"
echo "     puis RELANCER ce job : il relit le corps en direct, pas besoin de nouveau commit."
if [[ -n "$justifications" ]]; then
  echo ""
  echo "NB : une ligne Parity-Impact a été trouvée mais sa justification fait moins de" \
    "$min_justification_chars caractères — expliquer, pas cocher."
fi
echo ""
echo "Contexte : $matrix_page § Règle d'entretien."
echo "::error::Garde parité de lecture (#1045) : surface de lecture touchée sans mise à jour de $matrix_page ni ligne « Parity-Impact: none — <raison> » (>= $min_justification_chars caractères) dans le corps de la PR. Détail et façons de passer dans le log ci-dessus."
emit_verdict "blocked"
exit 1
