#!/usr/bin/env bash
# Garde Hilt avant publication (#1581), sans Gradle ni inspection de ses caches.
# Projets : include(...) littéraux de settings.gradle.kts, hors commentaires.
# Contributions : annotations Hilt simples sur des déclarations de premier niveau
# dans src/main/**/*.kt et .java, hors commentaires et littéraux. Les source sets
# test*/androidTest*/testFixtures/screenshotTest, debug et *Debug sont ignorés.
# Tout autre src/<set> contenant un token Hilt et tout ajout de sources Java/Kotlin
# dans les build files inclus ou build-logic sont refusés (pas de calcul de variante).
# Seule exception pour ces ajouts : sourceSets.getByName("<test/debug ignoré>")
# avec un nom littéral et un accès java/kotlin direct, sans résoudre de variable.
# Une occurrence Hilt non appariée est une erreur, jamais une contribution devinée
# ou ignorée.
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

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
list_expected=0
if [[ "${1:-}" == --list-expected && "$#" -le 2 ]]; then
  list_expected=1
  repo_root="${2:-$repo_root}"
  shift "$#"
elif [[ "$#" -eq 0 || "${1:-}" == --* ]]; then
  echo "Usage: bash scripts/release/verify-hilt-artifact.sh <apk|aab>..." >&2
  echo "       bash scripts/release/verify-hilt-artifact.sh --list-expected [repo-root]" >&2
  exit 1
fi

for tool in unzip grep find sort mktemp rm dirname awk; do
  command -v "$tool" >/dev/null || {
    echo "::error::Hilt: outil requis introuvable : $tool" >&2
    exit 1
  }
done

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

# Lexer puis reconnaissance volontairement bornée, en awk POSIX (sans gensub,
# captures de match, tableaux imbriqués, nextfile ou autre extension GNU).
# Les littéraux restent opaques ; settings les conserve pour include, build pour
# reconnaître exclusivement les noms littéraux des source sets test/debug ignorés.
# Un fichier à la fois ; les numéros de ligne survivent au nettoyage.
scan_code() {
  local mode="$1"
  shift
  local source_set=''
  if [[ "$mode" == other_sources ]]; then
    source_set="$1"
    shift
  fi
  awk -v mode="$mode" -v source_set="$source_set" '
    function target(word) {
      return word ~ /^(HiltViewModel|AndroidEntryPoint|HiltAndroidApp|InstallIn)$/
    }
    function identifier(word) { return word ~ /^[[:alpha:]_][[:alnum:]_]*$/ }
    function error_at(line, message) {
      print "::error::Hilt: " source_file ":" line ": forme non supportée : " message > "/dev/stderr"
    }
    function unpaired(    i, count) {
      for (i = 1; i <= token_count; i++) {
        if (!imported[i] && target(token[i]) && !paired[i]) {
          error_at(token_line[i], "occurrence " token[i] " non appariée")
          count++
        }
      }
      return count
    }
    function invalid(line, message) {
      error_at(line, message)
      if (mode == "sources") unpaired()
      aborted = 1
      exit 1
    }
    function emit(value, line) {
      token[++token_count] = value
      token_line[token_count] = line
    }
    function skip_comment(    depth, start_line, pair) {
      start_line = lex_line
      pair = substr(source, pos, 2)
      pos += 2
      if (pair == "//") {
        while (pos <= source_length && substr(source, pos, 1) != "\n") pos++
        return
      }
      depth = 1
      while (pos <= source_length) {
        pair = substr(source, pos, 2)
        # Seul Kotlin imbrique les commentaires ; Java ferme au premier */.
        if (kotlin && pair == "/*") { depth++; pos += 2 }
        else if (pair == "*/") { pos += 2; if (!--depth) return }
        else { if (substr(source, pos, 1) == "\n") lex_line++; pos++ }
      }
      invalid(start_line, "commentaire non terminé")
    }
    # Une interpolation reste dans le littéral retiré. Parcourir ses propres
    # chaînes/commentaires évite de fermer la chaîne sur un guillemet interne.
    function skip_interpolation(    depth, c, pair, start_line) {
      depth = 1
      start_line = lex_line
      while (pos <= source_length) {
        c = substr(source, pos, 1); pair = substr(source, pos, 2)
        if (pair == "//" || pair == "/*") skip_comment()
        else if (c == "\"" || c == apostrophe) skip_literal()
        else if (c == backtick) skip_identifier()
        else {
          pos++
          if (c == "\n") lex_line++
          else if (c == "{") depth++
          else if (c == "}" && !--depth) return
        }
      }
      invalid(start_line, "interpolation non terminée")
    }
    function skip_literal(    quote, width, start_line, c) {
      quote = substr(source, pos, 1)
      width = 1
      start_line = lex_line
      if (substr(source, pos, 3) == "\"\"\"") {
        if (java) invalid(start_line, "bloc texte Java")
        quote = "\"\"\""; width = 3
      }
      pos += width
      while (pos <= source_length) {
        c = substr(source, pos, 1)
        if (substr(source, pos, width) == quote) {
          pos += width
          # Kotlin permet un/deux guillemets de contenu juste avant la fermeture.
          if (width == 3) while (substr(source, pos, 1) == "\"") pos++
          return
        }
        if (c == "\n") {
          if (width == 1) invalid(start_line, "littéral non terminé")
          lex_line++
        }
        if (width == 1 && c == "\\") {
          if (substr(source, pos + 1, 1) == "\n") invalid(start_line, "littéral non terminé")
          pos += 2
        } else if (!java && quote != apostrophe && substr(source, pos, 2) == ("$" "{")) {
          pos += 2
          skip_interpolation()
        } else pos++
      }
      invalid(start_line, "littéral non terminé")
    }
    function skip_identifier(    start, start_line) {
      start = ++pos; start_line = lex_line
      while (pos <= source_length && substr(source, pos, 1) != backtick) {
        if (substr(source, pos, 1) == "\n") invalid(start_line, "identifiant échappé non terminé")
        pos++
      }
      if (pos > source_length) invalid(start_line, "identifiant échappé non terminé")
      pos++
      return substr(source, start, pos - start - 1)
    }
    function lex(    c, pair, start, start_line, value) {
      pos = lex_line = 1
      source_length = length(source)
      apostrophe = sprintf("%c", 39)
      backtick = sprintf("%c", 96)
      java = source_file ~ /\.java$/
      kotlin = source_file ~ /\.(kt|kts)$/
      # Java traite ces échappements avant même les commentaires/littéraux :
      # refuser cette syntaxe plutôt que masquer du code derrière un guillemet.
      if (java && match(source, /\\u/)) {
        value = substr(source, 1, RSTART)
        start_line = 1 + gsub(/\n/, "", value)
        invalid(start_line, "échappement Unicode Java")
      }
      while (pos <= source_length) {
        c = substr(source, pos, 1); pair = substr(source, pos, 2)
        start = pos; start_line = lex_line
        if (c ~ /[[:space:]]/) { if (c == "\n") lex_line++; pos++ }
        else if (pair == "//" || pair == "/*") skip_comment()
        else if (c == "\"" || c == apostrophe) {
          skip_literal()
          emit(mode == "settings" || mode == "build" ? substr(source, start, pos - start) : "<literal>", start_line)
        } else if (c == backtick) {
          value = skip_identifier()
          if (target(value) || value == "Module") invalid(start_line, "identifiant Hilt échappé")
          emit("<identifier>", start_line)
        } else if (c == "$" || c !~ /[[:print:]]/) {
          # Ne jamais tronquer un nom Java avec $ ou un identifiant Unicode.
          invalid(start_line, "caractère hors lexique ASCII reconnu")
        } else if (match(substr(source, pos), /^[[:alpha:]_][[:alnum:]_]*/)) {
          emit(substr(source, pos, RLENGTH), start_line)
          pos += RLENGTH
        } else { emit(c, start_line); pos++ }
      }
    }
    function settings_projects(    i, need_argument) {
      for (i = 1; i <= token_count; i++) {
        if (token[i] != "include") continue
        if (token[++i] != "(") invalid(token_line[i], "include non littéral")
        need_argument = 1
        for (i++; i <= token_count && token[i] != ")"; i++) {
          if (need_argument && token[i] ~ /^":[[:alnum:]_.:-]+"$/) {
            print substr(token[i], 3, length(token[i]) - 3)
            need_argument = 0
          } else if (!need_argument && token[i] == ",") need_argument = 1
          else invalid(token_line[i], "include non littéral")
        }
        if (i > token_count) invalid(lex_line, "include non terminé")
      }
    }
    function validate_import(start, end,    i) {
      i = start
      if (java && token[i] == "static") i++
      if (!identifier(token[i])) invalid(token_line[start], "import non reconnu")
      for (i++; i < end; i++) {
        if (token[i] == "." && (identifier(token[i + 1]) || token[i + 1] == "*")) {
          i++
          if (token[i] == "*" && i != end - 1) invalid(token_line[i], "import non reconnu")
        } else if (!java && token[i] == "as" && i == end - 2 && identifier(token[i + 1])) i++
        else invalid(token_line[i], "import non reconnu")
      }
    }
    function directives(    i, j, k, word, name, want_name, aliased, hilt_import) {
      package_name = ""
      for (i = 1; i <= token_count; i++) {
        word = token[i]
        if (word != "package" && word != "import") continue
        j = i + 1
        while (j <= token_count && token_line[j] == token_line[i] && token[j] != ";") j++
        name = ""; want_name = 1; aliased = hilt_import = 0
        for (k = i + 1; k < j; k++) {
          if (target(token[k]) || token[k] == "Module") hilt_import = 1
          if (token[k] == "as") aliased = 1
          if (word == "package") {
            if (want_name && identifier(token[k])) { name = name token[k]; want_name = 0 }
            else if (!want_name && token[k] == ".") { name = name "."; want_name = 1 }
            else invalid(token_line[k], "package non littéral")
          }
          ignored[k] = 1
          if (word == "import") imported[k] = 1
        }
        ignored[i] = 1
        if (mode == "sources" && aliased && hilt_import) invalid(token_line[i], "alias d annotation Hilt")
        if (word == "import") validate_import(i + 1, j)
        if (word == "package") {
          if (name == "" || want_name || package_name != "") invalid(token_line[i], "package non littéral")
          package_name = name
          if (!have_package++) common_package = name
          while (common_package != "" && name != common_package && index(name, common_package ".") != 1) {
            if (!sub(/\.[^.]+$/, "", common_package)) common_package = ""
          }
        }
        i = j - 1
      }
    }
    function balanced_end(start,    i, word, closing, stack) {
      for (i = start; i <= token_count; i++) {
        word = token[i]
        closing = word == "(" ? ")" : (word == "[" ? "]" : (word == "{" ? "}" : ""))
        if (closing != "") stack = closing stack
        else if (word == ")" || word == "]" || word == "}") {
          if (word != substr(stack, 1, 1)) invalid(token_line[i], "arguments d annotation déséquilibrés")
          stack = substr(stack, 2)
          if (stack == "") return i
        }
      }
      invalid(token_line[start], "annotation non terminée")
    }
    function annotation_end(start,    i) {
      annotation_name = ""; annotation_index = 0; simple = 1
      i = start + 1
      if (token[i] == "[") { simple = 0; return balanced_end(i) }
      if (!identifier(token[i])) { simple = 0; return start }
      if (token[i + 1] == ":") { simple = 0; i += 2 }
      if (!identifier(token[i])) return i
      while (token[i + 1] == "." && identifier(token[i + 2])) i += 2
      annotation_name = token[i]; annotation_index = i
      if (token[i + 1] == "(") i = balanced_end(i + 1)
      return i
    }
    function declarations(    i, end, word, name, kind, pending, pending_line, curly, parens, brackets, key) {
      for (i = 1; i <= token_count; i++) {
        if (ignored[i]) continue
        word = token[i]
        if (word == "@") {
          end = annotation_end(i)
          if (!simple && pending) invalid(token_line[i], "annotation non simple avant la déclaration Hilt")
          if (simple && (target(annotation_name) || annotation_name == "Module")) {
            if (curly || parens || brackets) invalid(token_line[i], "cible Hilt imbriquée (premier niveau requis)")
            if (seen[annotation_name]) invalid(token_line[i], "annotation Hilt répétée")
            seen[annotation_name] = annotation_index
            if (!pending) pending_line = token_line[i]
            pending = 1
          }
          i = end
          continue
        }
        if (pending) {
          if (word ~ /^(public|protected|private|internal|abstract|final|open|sealed|data|enum|annotation|value|expect|actual|external|static|strictfp)$/) continue
          if (word !~ /^(class|object|interface)$/) invalid(token_line[i], "token " word " avant la déclaration Hilt")
          kind = word
          name = token[++i]
          if (!identifier(name) || name ~ /^(class|object|interface)$/) invalid(token_line[i - 1], "déclaration Hilt sans nom simple")
          if ((seen["HiltViewModel"] || seen["HiltAndroidApp"] || seen["AndroidEntryPoint"]) && kind != "class") {
            invalid(pending_line, "annotation Hilt attendue sur une classe")
          }
          if (seen["InstallIn"] && !seen["Module"]) invalid(pending_line, "InstallIn sans Module sur la même déclaration")
          if (seen["HiltViewModel"] || seen["HiltAndroidApp"] || seen["AndroidEntryPoint"] || (seen["Module"] && seen["InstallIn"])) {
            if (package_name == "") invalid(pending_line, "contribution Hilt sans package")
            contributor = 1
            print "contribution\t" package_name "\t" name
            if (seen["HiltViewModel"]) print "viewmodel\t" package_name "\t" name
            if (seen["HiltAndroidApp"]) print "application\t" package_name "\t" name
            for (key in seen) {
              if (seen[key]) paired[seen[key]] = 1
              if (target(key) && seen[key]) retained[key]++
            }
          }
          for (key in seen) delete seen[key]
          pending = 0
        } else {
          if (word == "{") curly++
          else if (word == "}") curly--
          else if (word == "(") parens++
          else if (word == ")") parens--
          else if (word == "[") brackets++
          else if (word == "]") brackets--
          if (curly < 0 || parens < 0 || brackets < 0) invalid(token_line[i], "délimiteurs déséquilibrés")
        }
      }
      if (pending) invalid(pending_line, "annotation sans déclaration")
      if (curly || parens || brackets) invalid(lex_line, "délimiteurs non terminés")
    }
    function ignored_set_literal(value,    quote, name) {
      quote = substr(value, 1, 1)
      if ((quote != "\"" && quote != apostrophe) || substr(value, length(value), 1) != quote) return 0
      name = substr(value, 2, length(value) - 2)
      # Pas de concaténation, échappement, interpolation ou variable déduite.
      return identifier(name) && (name ~ /^(test|androidTest)/ || name == "screenshotTest" || name == "debug" || name ~ /Debug$/)
    }
    function ignored_directory(receiver) {
      # Reconnaissance locale de sourceSets.getByName("test").java/kotlin seulement.
      # Les autres sélecteurs, blocs ou alias restent refusés faute de preuve.
      return token[receiver - 1] == "." && token[receiver - 2] == ")" &&
        ignored_set_literal(token[receiver - 3]) && token[receiver - 4] == "(" &&
        token[receiver - 5] == "getByName" && token[receiver - 6] == "." && token[receiver - 7] == "sourceSets"
    }
    function custom_sources(    i, depth, word, receiver, receiver_index, language_scope, resource_scope) {
      # Accès Kotlin/Groovy directs ou via getters, y compris répartis sur plusieurs
      # lignes. Les blocs java { srcDirs(...) } / kotlin { directories += ... }
      # sont aussi refusés ; assets/res/resources restent hors de ces récepteurs.
      for (i = 1; i <= token_count; i++) {
        word = token[i]
        if (word == "{") {
          language_scope[depth + 1] = language_scope[depth] || token[i - 1] ~ /^(java|kotlin)$/
          resource_scope[depth + 1] = resource_scope[depth] || token[i - 1] ~ /^(assets|res|resources)$/
          depth++
        } else if (word == "}") {
          if (depth) depth--
        } else if (word ~ /^(srcDir|srcDirs|setSrcDirs|getSrcDirs|directories|getDirectories)$/) {
          receiver = ""
          receiver_index = 0
          if (token[i - 1] == ".") {
            receiver_index = i - 2
            if (token[receiver_index] == ")" && token[i - 3] == "(") receiver_index = i - 4
            receiver = token[receiver_index]
          }
          if (receiver ~ /^(assets|res|resources|getAssets|getRes|getResources)$/) continue
          if (receiver ~ /^(java|kotlin|getJava|getKotlin)$/ && ignored_directory(receiver_index)) continue
          # Un récepteur inconnu (alias, configure/apply, etc.) ne prouve pas une
          # source de test ni une ressource : refuser au lieu de deviner son type.
          if (receiver != "" || (!resource_scope[depth] &&
              (language_scope[depth] || word ~ /^(srcDir|srcDirs|setSrcDirs|getSrcDirs)$/))) {
            invalid(token_line[i], "répertoire de sources personnalisé non supporté")
          }
        }
      }
    }
    function finish_file(    i, key) {
      if (source_file == "") return
      lex()
      if (mode == "settings") settings_projects()
      else if (mode == "build") custom_sources()
      else {
        directives()
        for (i = 1; i <= token_count; i++) {
          if (mode == "other_sources" && !imported[i] && target(token[i])) {
            invalid(token_line[i], "source set " source_set " hors périmètre de la garde")
          }
          if (!imported[i] && target(token[i])) occurrences[token[i]]++
        }
        if (mode == "sources") declarations()
        # Comptage indépendant de la reconnaissance : un token avalé dans une
        # annotation groupée/autre argument, ou un usage indirect, fait échouer.
        if (unpaired()) { aborted = 1; exit 1 }
        for (key in occurrences) {
          if (occurrences[key] != retained[key]) invalid(1, "comptage Hilt incohérent : " key)
        }
      }
      for (i = 1; i <= token_count; i++) {
        delete token[i]; delete token_line[i]; delete ignored[i]; delete imported[i]; delete paired[i]
      }
      for (key in occurrences) delete occurrences[key]
      for (key in retained) delete retained[key]
      token_count = 0
    }
    FNR == 1 {
      finish_file()
      source_file = FILENAME
      source = ""
    }
    { source = source $0 "\n" }
    END {
      if (!aborted) {
        finish_file()
        if (mode == "sources" && contributor) {
          if (common_package == "") invalid(1, "contributions sans package commun")
          print "prefix\t" common_package "\t-"
        }
      }
    }
  ' "$@"
}

scan_code settings "$repo_root/settings.gradle.kts" | sort -u >"$work_dir/projects"

# Les chemins personnalisés visibles dans les scripts et conventions invalident
# le parcours src/<set>. Ne pas descendre dans les sorties de build-logic.
build_files=()
while IFS= read -r module; do
  for build_file in "$repo_root/${module//:/\/}/build.gradle"{,.kts}; do
    [[ ! -f "$build_file" ]] || build_files+=("$build_file")
  done
done <"$work_dir/projects"
for build_file in "$repo_root/build.gradle"{,.kts} "$repo_root/build-logic/build.gradle"{,.kts}; do
  [[ ! -f "$build_file" ]] || build_files+=("$build_file")
done
if [[ -d "$repo_root/build-logic/src" ]]; then
  find "$repo_root/build-logic/src" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.java' -o -name '*.gradle' -o -name '*.groovy' \) -print0 | sort -z >"$work_dir/build-logic-files"
  while IFS= read -r -d '' build_file; do build_files+=("$build_file"); done <"$work_dir/build-logic-files"
fi
if [[ "${#build_files[@]}" -gt 0 ]]; then
  scan_code build "${build_files[@]}"
fi

modules=()
check_modules=()
check_names=()
markers=()
application_found=0
while IFS= read -r module; do
  module_dir="$repo_root/${module//:/\/}"
  [[ -d "$module_dir/src" ]] || continue
  find "$module_dir/src" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z >"$work_dir/source-sets"
  while IFS= read -r -d '' source_dir; do
    source_set="${source_dir##*/}"
    case "$source_set" in
      main|test*|androidTest*|screenshotTest|debug|*Debug) continue ;;
    esac
    find "$source_dir" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.java' \) -print0 | sort -z >"$work_dir/other-source-files"
    mapfile -d '' -t source_files <"$work_dir/other-source-files"
    if [[ "${#source_files[@]}" -gt 0 ]]; then
      scan_code other_sources "$source_set" "${source_files[@]}"
    fi
  done <"$work_dir/source-sets"
  source_dir="$module_dir/src/main"
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
if [[ "$list_expected" -eq 1 ]]; then
  exit 0
fi

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
