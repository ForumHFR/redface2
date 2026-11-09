#!/usr/bin/env bash
# Garde Hilt avant publication (#1581), sans Gradle ni inspection de ses caches.
# Projets : include(...) littéraux de settings.gradle.kts, hors commentaires.
# Contributions : annotations Hilt dans src/main/**/*.kt et .java, hors commentaires.
# Chaque @HiltViewModel exige _<package en _>_<Classe>_HiltModules_KeyModule ;
# chaque @HiltAndroidApp exige Hilt_<Classe>. Sans ViewModel, un contributeur
# (@AndroidEntryPoint, @HiltAndroidApp ou @Module + @InstallIn sur une déclaration)
# exige aussi le préfixe agrégé du package commun lu dans ses sources.
# Hypothèse : DEX non obfusqué (isMinifyEnabled = false dans les conventions).
# Les fixtures v371-v377 ne sont pas minifiées. Si la minification est activée,
# revoir la garde et revérifier la conservation des marqueurs dans les APK/AAB
# finaux, ainsi que la détection des pertes totales et partielles.
# Présence d'octets seulement : ni définition de classe ni intégration au graphe
# de composants garanties ; les autres liaisons @Module ne sont pas exhaustives.
# APK : classes*.dex ; AAB : base/dex/classes*.dex. Seul le DEX final est lu.
set -euo pipefail
export LC_ALL=C

if [[ "$#" -eq 0 ]]; then
  echo "Usage: bash scripts/release/verify-hilt-artifact.sh <apk|aab>..." >&2
  exit 1
fi

for tool in unzip grep find sort mktemp rm dirname awk; do
  command -v "$tool" >/dev/null || {
    echo "::error::Hilt: outil requis introuvable : $tool" >&2
    exit 1
  }
done

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

# Lecture statique adaptée aux include littéraux et déclarations nommées du dépôt.
# Partager le retrait des commentaires // et /* ... */ entre settings et sources ;
# préserver les chaînes (notamment les chemins des include et les URL Kotlin).
scan_code() {
  local mode="$1"
  shift
  awk -v mode="$mode" '
    function uncomment(line,    result, i, c, next_c) {
      result = ""
      for (i = 1; i <= length(line); i++) {
        c = substr(line, i, 1)
        next_c = substr(line, i + 1, 1)
        if (block) {
          if (c == "*" && next_c == "/") { block = 0; i++ }
        } else if (quote != "") {
          result = result c
          if (escape) escape = 0
          else if (c == "\\") escape = 1
          else if (c == quote) quote = ""
        } else if (c == "/" && next_c == "/") break
        else if (c == "/" && next_c == "*") { block = 1; i++; result = result " " }
        else {
          result = result c
          if (c == "\"" || c == sprintf("%c", 39)) quote = c
        }
      }
      return result
    }
    function annotation(line, name) {
      return line ~ ("@([[:alnum:]_]+\\.)*" name "([^[:alnum:]_]|$)")
    }
    function invalid(message) {
      print "::error::Hilt: " FILENAME ": " message > "/dev/stderr"
      exit 1
    }
    FNR == 1 {
      if (view_model || application) invalid("annotation sans classe nommée")
      package_name = ""; block = 0; quote = ""; escape = 0
      view_model = application = entry_point = module = install_in = 0
    }
    {
      line = uncomment($0)
      if (mode == "settings") { settings = settings " " line; next }
      if (line ~ /^[[:space:]]*package[[:space:]]+/) {
        package_name = line
        sub(/^[[:space:]]*package[[:space:]]+/, "", package_name)
        sub(/[;[:space:]].*$/, "", package_name)
        if (!have_package++) common_package = package_name
        while (common_package != "" && package_name != common_package &&
               index(package_name, common_package ".") != 1) {
          if (!sub(/\.[^.]+$/, "", common_package)) common_package = ""
        }
      }
      if (annotation(line, "HiltViewModel")) view_model = 1
      if (annotation(line, "HiltAndroidApp")) application = 1
      if (annotation(line, "AndroidEntryPoint")) entry_point = 1
      if (annotation(line, "Module")) module = 1
      if (annotation(line, "InstallIn")) install_in = 1
      if (match(line, /(^|[[:space:]])(class|object|interface)[[:space:]]+[[:alnum:]_]+/)) {
        class_name = substr(line, RSTART, RLENGTH)
        sub(/^.*[[:space:]]/, "", class_name)
        if (view_model || application || entry_point || (module && install_in)) {
          if (package_name == "") invalid("contribution Hilt sans package")
          contributor = 1
          print "contribution\t" package_name "\t" class_name
          if (view_model) print "viewmodel\t" package_name "\t" class_name
          if (application) print "application\t" package_name "\t" class_name
        }
        view_model = application = entry_point = module = install_in = 0
      }
    }
    END {
      if (mode == "settings") {
        while (match(settings, /(^|[^[:alnum:]_])include[[:space:]]*\([^)]*\)/)) {
          arguments = substr(settings, RSTART, RLENGTH)
          settings = substr(settings, RSTART + RLENGTH)
          sub(/^[^(]*\(/, "", arguments); sub(/\)$/, "", arguments)
          while (match(arguments, /":[[:alnum:]_.:-]+"/)) {
            project = substr(arguments, RSTART + 2, RLENGTH - 3)
            print project
            arguments = substr(arguments, 1, RSTART - 1) substr(arguments, RSTART + RLENGTH)
          }
          gsub(/[[:space:],]/, "", arguments)
          if (arguments != "") invalid("include non littéral non pris en charge")
        }
      } else {
        if (view_model || application) invalid("annotation sans classe nommée")
        if (contributor) {
          if (common_package == "") invalid("contributions sans package commun")
          print "prefix\t" common_package "\t-"
        }
      }
    }
  ' "$@"
}

scan_code settings "$repo_root/settings.gradle.kts" | sort -u >"$work_dir/projects"

modules=()
check_modules=()
check_names=()
markers=()
application_found=0
while IFS= read -r module; do
  source_dir="$repo_root/${module//:/\/}/src/main"
  [[ -d "$source_dir" ]] || continue
  # Matérialiser les listes : toute erreur find/sort/awk doit arrêter la garde.
  find "$source_dir" -type f \( -name '*.kt' -o -name '*.java' \) -print0 | sort -z >"$work_dir/source-files"
  mapfile -d '' -t source_files <"$work_dir/source-files"
  [[ "${#source_files[@]}" -gt 0 ]] || continue
  scan_code sources "${source_files[@]}" | sort -u >"$work_dir/contributions"
  contributor=0
  module_viewmodels=()
  module_prefix=''
  while IFS=$'\t' read -r kind package_name class_name; do
    case "$kind" in
      contribution) contributor=1 ;;
      prefix) module_prefix="_${package_name//./_}_" ;;
      viewmodel|application)
        check_modules+=("$module")
        check_names+=("$package_name.$class_name")
        if [[ "$kind" == viewmodel ]]; then
          module_viewmodels+=("$package_name.$class_name")
          # Le ; borne le descripteur exact : un nom plus long ne peut le masquer.
          markers+=("Lhilt_aggregated_deps/_${package_name//./_}_${class_name}_HiltModules_KeyModule;")
        else
          application_found=1
          markers+=("Hilt_$class_name")
        fi
        ;;
    esac
  done <"$work_dir/contributions"
  if [[ "$contributor" -eq 1 ]]; then
    modules+=("$module")
    if [[ "${#module_viewmodels[@]}" -eq 0 ]]; then
      check_modules+=("$module")
      check_names+=("préfixe de package $module_prefix")
      markers+=("Lhilt_aggregated_deps/$module_prefix")
      echo "Hilt: $module : préfixe $module_prefix"
    else
      echo "Hilt: $module : ViewModels : ${module_viewmodels[*]}"
    fi
  fi
done <"$work_dir/projects"

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
  for index in "${!markers[@]}"; do
    if ! grep -aF -- "${markers[$index]}" "$work_dir/artifact.dex" >/dev/null; then
      echo "::error::Hilt: $artifact : module ${check_modules[$index]} : ${check_names[$index]} absent du DEX (marqueur ${markers[$index]})" >&2
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
