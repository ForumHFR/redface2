#!/usr/bin/env bash
# Garde Hilt avant publication (#1581), sans Gradle ni inspection de ses caches.
# Les modules sont dérivés à chaque run des build.gradle.kts qui appliquent
# redface.android.hilt.library : leur chemin core/foo ou feature/foo donne le
# préfixe hilt_aggregated_deps _fr_forumhfr_redface2_core_foo_ (idem feature).
# app applique redface.android.hilt.application, mais son package est à la racine
# fr.forumhfr.redface2, sans segment "app" : on exige Hilt_RedfaceApplication.
# Aucune bibliothèque n'est exemptée pour R8 : tous les préfixes sont présents dans
# les fixtures release minifiées v377. Toute future exception doit être prouvée.
# APK : classes*.dex ; AAB : base/dex/classes*.dex. Seul le DEX final est lu.
set -euo pipefail
export LC_ALL=C

if [[ "$#" -eq 0 ]]; then
  echo "Usage: bash scripts/release/verify-hilt-artifact.sh <apk|aab>..." >&2
  exit 1
fi

for tool in unzip grep find sort mktemp rm dirname; do
  command -v "$tool" >/dev/null || {
    echo "::error::Hilt: outil requis introuvable : $tool" >&2
    exit 1
  }
done

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

# Matérialiser la liste : une erreur de find/sort doit arrêter la garde.
find "$repo_root" -type d \( -name .git -o -name build -o -name '.gradle*' \
  -o -name .kotlin -o -name node_modules \) -prune -o \
  -type f -name build.gradle.kts -print0 | sort -z >"$work_dir/build-files"

modules=()
prefixes=()
application_found=0
while IFS= read -r -d '' build_file; do
  module="${build_file#"$repo_root/"}"
  module="${module%/build.gradle.kts}"
  if grep -Eq '^[[:space:]]*id\([[:space:]]*"redface\.android\.hilt\.application"[[:space:]]*\)' "$build_file"; then
    [[ "$module" == app ]] || {
      echo "::error::Hilt: module application inattendu : $module" >&2
      exit 1
    }
    application_found=1
    modules+=(app)
    prefixes+=(Hilt_RedfaceApplication)
  elif grep -Eq '^[[:space:]]*id\([[:space:]]*"redface\.android\.hilt\.library"[[:space:]]*\)' "$build_file"; then
    modules+=("${module//\//:}")
    # Le séparateur final évite qu'un module foobar masque la perte de foo.
    prefixes+=("_fr_forumhfr_redface2_${module//\//_}_")
  fi
done <"$work_dir/build-files"

if [[ "$application_found" -ne 1 || "${#modules[@]}" -lt 2 ]]; then
  echo "::error::Hilt: dérivation incomplète (app et bibliothèques Hilt attendues)" >&2
  exit 1
fi
echo "Hilt: modules attendus : ${modules[*]}"

failed=0
for artifact in "$@"; do
  case "$artifact" in
    *.apk) dex_pattern='classes*.dex' ;;
    *.aab) dex_pattern='base/dex/classes*.dex' ;;
    *)
      echo "::error::Hilt: $artifact : extension attendue .apk ou .aab" >&2
      failed=1
      continue
      ;;
  esac
  # Pas de pipe vers grep -q : il fermerait le pipe avant la fin d'unzip
  # (SIGPIPE avec pipefail). Vérifier aussi le code unzip : ZIP absent/corrompu
  # ou sans DEX ne doit jamais passer, même si des octets ont pu être extraits.
  if ! unzip -p "$artifact" "$dex_pattern" >"$work_dir/artifact.dex"; then
    echo "::error::Hilt: $artifact : lecture DEX impossible ($dex_pattern)" >&2
    failed=1
    continue
  fi
  artifact_failed=0
  for index in "${!modules[@]}"; do
    if ! grep -aF -- "${prefixes[$index]}" "$work_dir/artifact.dex" >/dev/null; then
      echo "::error::Hilt: $artifact : module ${modules[$index]} absent du DEX (marqueur ${prefixes[$index]})" >&2
      artifact_failed=1
    fi
  done
  if [[ "$artifact_failed" -eq 0 ]]; then
    echo "OK: Hilt: $artifact (${#modules[@]} modules)"
  else
    failed=1
  fi
done
exit "$failed"
