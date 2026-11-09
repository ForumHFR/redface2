#!/usr/bin/env bash
# Régressions : dépôts et archives synthétiques temporaires, aucun Gradle.
# zip est requis pour les replays de bout en bout ; aucun APK réel n'est créé.
set -euo pipefail
export LC_ALL=C

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT
case_count=0

fail() {
  echo "FAIL: $case_name : $*" >&2
  cat "$work_dir/stdout" "$work_dir/stderr" >&2
  exit 1
}

# App + une bibliothèque Hilt stables conservent les préconditions du contrôle DEX,
# même lorsque le module testé ne contient aucune contribution.
new_case() {
  case_name="$1"
  case_count=$((case_count + 1))
  case_root="$work_dir/$case_name"
  source_file="$case_root/${4:-feature/example}/src/${3:-main}/Example.${2:-kt}"
  mkdir -p "$case_root/app/src/main" "$case_root/core/base/src/main" \
    "$(dirname -- "$source_file")"
  cat >"$case_root/settings.gradle.kts" <<'SETTINGS'
/* /* commentaire imbriqué */ include(":not:included") */
val text = "include(\":not:included\")"
include(
    ":app",
    ":core:base",
    ":feature:example",
)
SETTINGS
  cat >"$case_root/app/src/main/App.kt" <<'APP'
package p.app
@HiltAndroidApp
class App
APP
  cat >"$case_root/core/base/src/main/Base.kt" <<'BASE'
package p.base
@Module @InstallIn(SingletonComponent::class) object Base
BASE
  cat >"$source_file"
}

derive() {
  set +e
  bash "$script_dir/verify-hilt-artifact.sh" --list-expected "$case_root" \
    >"$work_dir/stdout" 2>"$work_dir/stderr"
  rc=$?
  set -e
}

# Sortie complète exacte : vérifier aussi qu'aucun ViewModel n'est inventé.
# Argument vide = module sans contribution, absent de la liste des modules.
expect_list() {
  local expected="${1:-}"
  derive
  [[ "$rc" -eq 0 ]] || fail "code attendu 0, obtenu $rc"
  [[ ! -s "$work_dir/stderr" ]] || fail "stderr inattendu"
  {
    echo "Hilt: app : préfixe _p_app_"
    echo "Hilt: core:base : préfixe _p_base_"
    if [[ -n "$expected" ]]; then
      echo "Hilt: feature:example : $expected"
      echo "Hilt: modules attendus : app core:base feature:example"
    else
      echo "Hilt: modules attendus : app core:base"
    fi
  } >"$work_dir/expected"
  diff -u "$work_dir/expected" "$work_dir/stdout" || fail "liste différente"
  echo "OK: $case_name"
}

# Chaque paire ligne/message doit figurer dans un diagnostic localisé.
expect_unsupported() {
  derive
  [[ "$rc" -ne 0 ]] || fail "forme non supportée acceptée"
  while [[ "$#" -gt 0 ]]; do
    grep -F -- "::error::Hilt: $source_file:$1: forme non supportée : $2" \
      "$work_dir/stderr" >/dev/null || fail "diagnostic attendu ligne $1 : $2"
    shift 2
  done
  echo "OK: $case_name (forme non supportée)"
}

new_case simple <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel
@HiltViewModel
class Simple
SOURCE
expect_list "ViewModels : p.fixture.Simple"

new_case internal_multiline <<'SOURCE'
package p.fixture
@HiltViewModel(
    assistedFactory = Real.Factory::class,
)
@Suppress(
    "a class Fake",
    nested = Other(values = [")", "{", "HiltViewModel"]),
)
internal class Real
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case module_object <<'SOURCE'
package p.fixture
@Module @InstallIn(SingletonComponent::class) internal object Bindings
SOURCE
expect_list "préfixe _p_fixture_"

new_case java_simple java <<'SOURCE'
package p.fixture;
import dagger.hilt.android.lifecycle.HiltViewModel;
@HiltViewModel
@SuppressWarnings("a class Fake")
public final class JavaVm extends ViewModel {}
SOURCE
expect_list "ViewModels : p.fixture.JavaVm"

new_case java_comment_delimiter java <<'SOURCE'
package p.fixture;
import dagger.hilt.android.lifecycle.HiltViewModel;
/* Example delimiter: /* */
@HiltViewModel
public final class Hidden extends ViewModel {
    @Inject public Hidden() {}
}
// End example: */
SOURCE
expect_list "ViewModels : p.fixture.Hidden"

new_case java_comment_delimiter_without_end java <<'SOURCE'
package p.fixture;
/* Example delimiter: /* */
@HiltViewModel public final class Hidden extends ViewModel {}
SOURCE
expect_list "ViewModels : p.fixture.Hidden"

new_case kotlin_comment_delimiter <<'SOURCE'
package p.fixture
/* Example delimiter: /* */
@HiltViewModel class Hidden
// End example: */
SOURCE
expect_list

new_case quoted_noise <<'SOURCE'
package p.fixture
val annotation = "@HiltViewModel class Ghost"
val escaped = "\"@HiltViewModel class Escaped\" \\ // /*"
val raw = """
@HiltViewModel class Raw
@AndroidEntryPoint @HiltAndroidApp @InstallIn
"""
val quoteAtEnd = """some "text""""
val ch = '{'
val apostrophe = '\''
val slash = '\\'
class Plain
SOURCE
expect_list

new_case interpolation <<'SOURCE'
package p.fixture
val text = "${items.joinToString("@HiltViewModel class Fake")}"
val raw = """${run { /* } */ "${"@HiltViewModel"}" }}"""
@HiltViewModel class Real
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case nested_comments <<'SOURCE'
package p.fixture
// @HiltViewModel class Line
/* /* nested */ @HiltViewModel class Ghost */
/* outer
   /* /* deep */ @HiltViewModel class Deep */
   @AndroidEntryPoint class Commented
*/
@HiltViewModel
@Suppress("a class Fake")
class Real
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case grouped <<'SOURCE'
package p.fixture
@[HiltViewModel Suppress("unused")] class Grouped
SOURCE
expect_unsupported 2 "occurrence HiltViewModel non appariée"

new_case nested_kotlin <<'SOURCE'
package p.fixture
class Outer {
    @HiltViewModel class Inner
}
SOURCE
expect_unsupported 3 "cible Hilt imbriquée"

new_case nested_java java <<'SOURCE'
package p.fixture;
public class Outer {
    @HiltViewModel public static class Inner extends ViewModel {}
}
SOURCE
expect_unsupported 3 "cible Hilt imbriquée"

new_case two_classes <<'SOURCE'
package p.fixture
class Outer {} @HiltViewModel class Real
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case first_declaration <<'SOURCE'
package p.fixture
@HiltViewModel class First {} class Second
SOURCE
expect_list "ViewModels : p.fixture.First"

new_case two_viewmodels <<'SOURCE'
package p.fixture
@HiltViewModel class First {} @HiltViewModel class Second
SOURCE
expect_list "ViewModels : p.fixture.First p.fixture.Second"

new_case split_class_name <<'SOURCE'
package p.fixture
@HiltViewModel
class
Real
class Other
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case unexpected_declaration <<'SOURCE'
package p.fixture
@HiltViewModel
fun unrelated() {}
class Later
SOURCE
expect_unsupported 3 "token fun avant la déclaration Hilt"

new_case unexpected_literal <<'SOURCE'
package p.fixture
@HiltViewModel "class Fake"
class Later
SOURCE
expect_unsupported 2 "token <literal> avant la déclaration Hilt"

new_case wrong_kind <<'SOURCE'
package p.fixture
@HiltViewModel object Wrong
class Later
SOURCE
expect_unsupported 2 "annotation Hilt attendue sur une classe"

new_case dangling <<'SOURCE'
package p.fixture
@HiltViewModel
SOURCE
expect_unsupported 2 "annotation sans déclaration"

new_case unmatched_tokens <<'SOURCE'
package p.fixture
val first = HiltViewModel::class
val second = AndroidEntryPoint::class
val third = HiltAndroidApp::class
val fourth = InstallIn::class
SOURCE
expect_unsupported 2 "occurrence HiltViewModel non appariée" \
  3 "occurrence AndroidEntryPoint non appariée" \
  4 "occurrence HiltAndroidApp non appariée" \
  5 "occurrence InstallIn non appariée"

new_case token_in_annotation_argument <<'SOURCE'
package p.fixture
@Other(HiltViewModel::class)
class Plain
SOURCE
expect_unsupported 2 "occurrence HiltViewModel non appariée"

new_case annotation_alias <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel as Vm
@Vm class Hidden
SOURCE
expect_unsupported 2 "alias d annotation Hilt"

new_case qualified_annotation <<'SOURCE'
package p.fixture
@dagger.hilt.android.lifecycle.HiltViewModel class Qualified
SOURCE
expect_list "ViewModels : p.fixture.Qualified"

new_case application_and_entry <<'SOURCE'
package p.fixture
@HiltAndroidApp class AnotherApp
@AndroidEntryPoint class Activity
SOURCE
expect_list "préfixe _p_fixture_"

new_case grouped_entry_and_application <<'SOURCE'
package p.fixture
@[AndroidEntryPoint HiltAndroidApp] class Unsupported
SOURCE
expect_unsupported 2 "occurrence AndroidEntryPoint non appariée" \
  2 "occurrence HiltAndroidApp non appariée"

new_case install_without_module <<'SOURCE'
package p.fixture
@InstallIn(SingletonComponent::class) interface Entry
SOURCE
expect_unsupported 2 "InstallIn sans Module sur la même déclaration"

new_case plain_module <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel
class Plain
SOURCE
expect_list

new_case distinct_files <<'SOURCE'
package p.fixture
@HiltViewModel class First
SOURCE
cat >"$case_root/feature/example/src/main/Second.kt" <<'SOURCE'
package p.fixture
@HiltViewModel class Second
SOURCE
expect_list "ViewModels : p.fixture.First p.fixture.Second"

new_case java_unicode java <<'SOURCE'
package p.fixture;
\u0040HiltViewModel class Hidden {}
SOURCE
expect_unsupported 2 "échappement Unicode Java"

new_case unterminated_comment <<'SOURCE'
package p.fixture
/* /* nested */
@HiltViewModel class Hidden
SOURCE
expect_unsupported 2 "commentaire non terminé"

new_case java_dollar_name java <<'SOURCE'
package p.fixture;
@HiltViewModel class Wrong$Name extends ViewModel {}
SOURCE
expect_unsupported 2 "caractère hors lexique ASCII reconnu"

new_case unicode_class_name <<'SOURCE'
package p.fixture
@HiltViewModel class Realé
SOURCE
expect_unsupported 2 "caractère hors lexique ASCII reconnu"

new_case escaped_class_name <<'SOURCE'
package p.fixture
@HiltViewModel class `Real`
SOURCE
expect_unsupported 2 "déclaration Hilt sans nom simple"

new_case escaped_annotation_name <<'SOURCE'
package p.fixture
@`HiltViewModel` class Hidden
SOURCE
expect_unsupported 2 "identifiant Hilt échappé"

new_case interpolation_escaped_identifier <<'SOURCE'
package p.fixture
val value = "${obj.`a}b`}" // @HiltViewModel
@HiltViewModel class Real
SOURCE
expect_list "ViewModels : p.fixture.Real"

new_case token_in_package <<'SOURCE'
package p.HiltViewModel
class Plain
SOURCE
expect_unsupported 1 "occurrence HiltViewModel non appariée"

new_case unsupported_import <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel @HiltViewModel class Hidden
SOURCE
expect_unsupported 2 "import non reconnu"

new_case module_separate_declarations <<'SOURCE'
package p.fixture
@Module object First {} @InstallIn(SingletonComponent::class) object Second
SOURCE
expect_unsupported 2 "InstallIn sans Module sur la même déclaration"

new_case grouped_install <<'SOURCE'
package p.fixture
@[Module InstallIn(SingletonComponent::class)] object Hidden
SOURCE
expect_unsupported 2 "occurrence InstallIn non appariée"

new_case java_text_block java <<'SOURCE'
package p.fixture;
class Plain { String text = """
@HiltViewModel class Hidden
"""; }
SOURCE
expect_unsupported 2 "bloc texte Java"

expect_source_set() {
  derive
  [[ "$rc" -eq 1 ]] || fail "source set non supporté : code attendu 1, obtenu $rc"
  grep -Fx -- "::error::Hilt: source set $1/src/$2 non supporté par la garde" \
    "$work_dir/stderr" >/dev/null || fail "diagnostic source set $1/src/$2 absent"
  echo "OK: $case_name (source set non supporté)"
}

# Le nom seul suffit : aucune lecture ni présence de Hilt nécessaire.
for source_set in release prod prodDebug internalDebug screenshotTest devRelease beta unknown testDebug androidTestProd; do
  new_case "empty_$source_set" kt "$source_set" </dev/null
  rm -- "$source_file"
  expect_source_set feature/example "$source_set"
done

for source_set in test androidTest testFixtures debug; do
  new_case "ignored_$source_set" java "$source_set" <<'SOURCE'
package p.fixture;
// Même une syntaxe refusée dans main ne doit pas être analysée ici.
class Plain { String text = """
@HiltViewModel class Hidden
"""; }
SOURCE
  expect_list
done

new_case release_alias kt release <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel as HV
@HV class Hidden
SOURCE
expect_source_set feature/example release

new_case app_prod_alias kt prod app <<'SOURCE'
package p.app
import dagger.hilt.android.lifecycle.HiltViewModel as HV
@HV class Hidden
SOURCE
expect_source_set app prod

new_build_case() {
  local name="$1" path="$2"
  new_case "$name" <<'SOURCE'
package p.fixture
@HiltViewModel class Real
SOURCE
  source_file="$case_root/$path"
  mkdir -p "$(dirname -- "$source_file")"
  cat >"$source_file"
}

expect_configuration() {
  expect_unsupported "$1" "configuration de sources non supportée par la garde"
}

# Chaque token est refusé indépendamment du récepteur et du DSL.
source_tokens=(
  sourceSets getSourceSets srcDir srcDirs setSrcDirs getSrcDirs setRoot root
  directories getDirectories sources getSources addSource
  addStaticSourceDirectory addGeneratedSourceDirectory
)
for source_token in "${source_tokens[@]}"; do
  new_build_case "token_$source_token" feature/example/build.gradle.kts <<BUILD
val value = $source_token
BUILD
  expect_configuration 1
done

# Tous les fichiers de configuration prévus sont lus, y compris les settings de
# build-logic ; les settings racine Groovy sont acceptés pour les include littéraux.
for build_path in \
  settings.gradle settings.gradle.kts build.gradle build.gradle.kts \
  feature/example/build.gradle feature/example/build.gradle.kts \
  build-logic/settings.gradle build-logic/settings.gradle.kts \
  build-logic/build.gradle build-logic/build.gradle.kts \
  build-logic/src/main/kotlin/Convention.kt \
  build-logic/src/main/kotlin/Convention.kts \
  build-logic/src/main/java/Convention.java; do
  new_build_case "config_${build_path//\//_}" "$build_path" <<'BUILD'
// Comment on line one.
sources.addSource("hidden")
BUILD
  expect_configuration 2
done

for settings_token in projectDir setProjectDir buildFileName; do
  for settings_path in settings.gradle settings.gradle.kts build-logic/settings.gradle build-logic/settings.gradle.kts; do
    new_build_case "settings_${settings_path//\//_}_$settings_token" "$settings_path" <<BUILD
project(":x").$settings_token = file("y")
BUILD
    expect_configuration 1
  done
done

new_build_case settings_tokens_in_build feature/example/build.gradle.kts <<'BUILD'
val path = projectDir
setProjectDir(file("y"))
val name = buildFileName
BUILD
expect_list "ViewModels : p.fixture.Real"

new_build_case implicit_apply feature/example/build.gradle.kts <<'BUILD'
sourceSets.getByName("main").kotlin.apply { directories += "hidden" }
BUILD
expect_configuration 1

new_build_case implicit_with feature/example/build.gradle.kts <<'BUILD'
with(sourceSets.getByName("main").kotlin) { directories += "hidden" }
BUILD
expect_configuration 1

new_build_case release_set_root feature/example/build.gradle.kts <<'BUILD'
sourceSets.getByName("release").setRoot("hidden")
BUILD
expect_configuration 1

new_build_case android_components feature/example/build.gradle.kts <<'BUILD'
androidComponents { onVariants { it.sources.kotlin?.addStaticSourceDirectory("x") } }
BUILD
expect_configuration 1

new_build_case multiline_config feature/example/build.gradle.kts <<'BUILD'
sourceSets
    .getByName("main").kotlin
BUILD
expect_configuration 1

new_build_case escaped_config_identifier feature/example/build.gradle.kts <<'BUILD'
`sourceSets`.getByName("main")
BUILD
expect_configuration 1

new_build_case groovy_comment feature/example/build.gradle <<'BUILD'
/* Example delimiter: /* */
sourceSets.main.java.srcDir('x')
// End example: */
BUILD
expect_configuration 2

new_build_case exact_identifiers feature/example/build.gradle.kts <<'BUILD'
val sourceSetsExtra = 1
val resources = 2
val rootProjectName = 3
val mydirectories = 4
BUILD
expect_list "ViewModels : p.fixture.Real"

new_build_case config_comment_and_literal feature/example/build.gradle.kts <<'BUILD'
// sourceSets getSourceSets srcDir srcDirs setSrcDirs getSrcDirs setRoot root
/* directories getDirectories sources getSources addSource
   addStaticSourceDirectory addGeneratedSourceDirectory */
/* /* nested */ directories += "x" */
val sample = "sourceSets.getByName(\"main\").kotlin.directories += \"x\""
val raw = """sources addGeneratedSourceDirectory root "directories""""
val interpolated = "${run { /* sources */ "sourceSets" }}"
BUILD
expect_list "ViewModels : p.fixture.Real"

new_case settings_comment_and_literal <<'SOURCE'
package p.fixture
@HiltViewModel class Real
SOURCE
cat >>"$case_root/settings.gradle.kts" <<'SETTINGS'
// sourceSets root sources projectDir setProjectDir buildFileName
/* project(":x").projectDir = file("y") */
val ignored = "projectDir setProjectDir buildFileName srcDir"
val raw = """sourceSets root sources"""
SETTINGS
expect_list "ViewModels : p.fixture.Real"

new_case groovy_settings <<'SOURCE'
package p.fixture
@HiltViewModel class Real
SOURCE
mv "$case_root/settings.gradle.kts" "$case_root/settings.gradle"
cat >"$case_root/settings.gradle" <<'SETTINGS'
// projectDir setProjectDir buildFileName sourceSets
include(':app', ':core:base', ':feature:example')
SETTINGS
expect_list "ViewModels : p.fixture.Real"

database_path=core/database/build.gradle.kts
roborazzi_path=build-logic/src/main/kotlin/fr/forumhfr/redface2/buildlogic/RedfaceAndroidRoborazziConventionPlugin.kt
# shellcheck disable=SC2016 # literal Gradle line, $projectDir must not expand
database_line='assets.directories.add("$projectDir/schemas")'
roborazzi_line='sourceSets.getByName("test").kotlin.directories +='

new_build_case whitelist_database "$database_path" <<'BUILD'
android {
    sourceSets {
        named("test") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}
BUILD
# Ce projet doit être inclus pour que son build file soit effectivement contrôlé.
echo 'include(":core:database")' >>"$case_root/settings.gradle.kts"
expect_list "ViewModels : p.fixture.Real"

new_build_case whitelist_roborazzi "$roborazzi_path" <<'BUILD'
sourceSets.getByName("test").kotlin.directories +=
    rootProject.layout.projectDirectory.dir("build-logic/src/roborazziTest/kotlin").asFile.absolutePath
BUILD
expect_list "ViewModels : p.fixture.Real"

new_build_case whitelist_trim "$roborazzi_path" </dev/null
printf '\t  %s  \t\n' "$roborazzi_line" >"$source_file"
expect_list "ViewModels : p.fixture.Real"

new_build_case whitelist_database_changed "$database_path" <<'BUILD'
assets.directories.add("$projectDir/other-schemas")
BUILD
echo 'include(":core:database")' >>"$case_root/settings.gradle.kts"
expect_configuration 1

new_build_case whitelist_roborazzi_changed "$roborazzi_path" <<'BUILD'
sourceSets.getByName("main").kotlin.directories +=
BUILD
expect_configuration 1

new_build_case whitelist_source_sets_changed "$database_path" <<'BUILD'
sourceSets{ // Même effet Gradle ; ligne différente.
BUILD
echo 'include(":core:database")' >>"$case_root/settings.gradle.kts"
expect_configuration 1

for whitelist_line in 'sourceSets {' "$database_line" "$roborazzi_line"; do
  new_build_case "whitelist_wrong_file_$case_count" feature/example/build.gradle.kts <<<"$whitelist_line"
  expect_configuration 1
done

new_build_case whitelist_extra_code "$database_path" <<'BUILD'
sourceSets { srcDir("hidden")
BUILD
echo 'include(":core:database")' >>"$case_root/settings.gradle.kts"
expect_configuration 1

# Les tableaux de lignes et les tokens ne doivent pas fuir entre deux fichiers.
new_build_case whitelist_then_other_file "$roborazzi_path" <<<"$roborazzi_line"
mkdir -p "$case_root/build-logic/src/main/kotlin/zz"
source_file="$case_root/build-logic/src/main/kotlin/zz/Other.kt"
printf '%s\n' "$roborazzi_line" >"$source_file"
expect_configuration 1

# Les tests et les autres extensions de build-logic ne sont pas de la configuration.
for build_path in \
  build-logic/src/test/java/TextBlockTest.java \
  build-logic/src/roborazziTest/java/TextBlockTest.java \
  build-logic/src/debug/java/TextBlockTest.java \
  build-logic/src/main/ignored.gradle \
  build-logic/src/main/ignored.groovy; do
  new_build_case "ignored_${build_path//\//_}" "$build_path" <<'BUILD'
class TextBlockTest { String text = """
sourceSets.main.java.srcDir("hidden")
"""; }
BUILD
  expect_list "ViewModels : p.fixture.Real"
done

new_build_case unincluded_custom_dir not/included/build.gradle.kts <<'BUILD'
sourceSets.main.java.srcDir("hidden")
BUILD
expect_list "ViewModels : p.fixture.Real"

# Bout en bout : ZIP valide mais DEX factice, avec les seuls marqueurs de l'app et
# de core:base. Hidden manque délibérément. La copie du script utilise le dépôt du
# cas comme racine, sans modifier le checkout réel ni invoquer Gradle.
mkdir -p "$work_dir/archive"
printf '%s\n' 'Hilt_App' 'Lhilt_aggregated_deps/_p_app_' \
  'Lhilt_aggregated_deps/_p_base_' >"$work_dir/archive/classes.dex"
(cd "$work_dir/archive" && zip -q "$work_dir/without-hidden.apk" classes.dex)

run_artifact() {
  mkdir -p "$case_root/scripts/release"
  cp "$script_dir/verify-hilt-artifact.sh" "$case_root/scripts/release/"
  set +e
  bash "$case_root/scripts/release/verify-hilt-artifact.sh" "$work_dir/without-hidden.apk" \
    >"$work_dir/stdout" 2>"$work_dir/stderr"
  rc=$?
  set -e
}

expect_artifact_rejected() {
  run_artifact
  [[ "$rc" -eq 1 ]] || fail "archive sans Hidden acceptée (rc=$rc)"
  grep -F -- "$1" "$work_dir/stderr" >/dev/null || fail "diagnostic attendu : $1"
  echo "OK: $case_name (archive synthétique rejetée)"
}

new_case artifact_control <<'SOURCE'
package p.fixture
class Plain
SOURCE
run_artifact
[[ "$rc" -eq 0 && ! -s "$work_dir/stderr" ]] || fail "archive témoin rejetée (rc=$rc)"
grep -Fx -- "OK: Hilt: $work_dir/without-hidden.apk (2 modules)" \
  "$work_dir/stdout" >/dev/null || fail "succès de l'archive témoin absent"
echo "OK: $case_name"

for source_set in release prod; do
  module_path=feature/example
  [[ "$source_set" != prod ]] || module_path=app
  new_case "artifact_alias_$source_set" kt "$source_set" "$module_path" <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel as HV
@HV class Hidden
SOURCE
  expect_artifact_rejected "::error::Hilt: source set $module_path/src/$source_set non supporté par la garde"
done

custom_configs=(
  'sourceSets.getByName("main").kotlin.apply { directories += "hidden" }'
  'with(sourceSets.getByName("main").kotlin) { directories += "hidden" }'
  'sourceSets.getByName("release").setRoot("hidden")'
)
for index in "${!custom_configs[@]}"; do
  new_case "artifact_custom_sources_$index" <<'SOURCE'
package p.fixture
class Plain
SOURCE
  mkdir -p "$case_root/feature/example/hidden"
  cat >"$case_root/feature/example/hidden/Hidden.kt" <<'SOURCE'
package p.fixture
@HiltViewModel class Hidden
SOURCE
  printf '%s\n' "${custom_configs[$index]}" >"$case_root/feature/example/build.gradle.kts"
  expect_artifact_rejected "::error::Hilt: $case_root/feature/example/build.gradle.kts:1: forme non supportée : configuration de sources non supportée par la garde"
done

new_case artifact_flavor_internalDebug kt internalDebug <<'SOURCE'
package p.fixture
@HiltViewModel class Hidden
SOURCE
cat >"$case_root/feature/example/build.gradle.kts" <<'BUILD'
android {
    flavorDimensions += "channel"
    productFlavors { create("internalDebug") { dimension = "channel" } }
}
BUILD
expect_artifact_rejected "::error::Hilt: source set feature/example/src/internalDebug non supporté par la garde"

echo "OK: $case_count cas de régression Hilt"
