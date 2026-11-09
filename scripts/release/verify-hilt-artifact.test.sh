#!/usr/bin/env bash
# Régressions de dérivation seules : dépôts temporaires, aucun APK ni Gradle.
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

for source_set in release prod devRelease beta unknown; do
  new_case "source_set_$source_set" kt "$source_set" <<'SOURCE'
package p.fixture
@HiltViewModel class Hidden
SOURCE
  expect_unsupported 2 "source set $source_set hors périmètre de la garde"
done

new_case app_prod kt prod app <<'SOURCE'
package p.app
@HiltViewModel class Hidden
SOURCE
expect_unsupported 2 "source set prod hors périmètre de la garde"

for source_set in debug prodDebug test androidTest testDebug androidTestProd testFixtures screenshotTest; do
  new_case "ignored_$source_set" kt "$source_set" <<'SOURCE'
package p.fixture
@HiltViewModel class Ignored
SOURCE
  expect_list
done

for hilt_token in HiltViewModel AndroidEntryPoint HiltAndroidApp InstallIn; do
  new_case "release_$hilt_token" kt release <<SOURCE
package p.fixture
val entry = $hilt_token::class
SOURCE
  expect_unsupported 2 "source set release hors périmètre de la garde"
done

new_case release_quoted_noise kt release <<'SOURCE'
package p.fixture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.InstallIn
// @HiltViewModel class Commented
/* @AndroidEntryPoint @HiltAndroidApp @InstallIn */
val text = "@HiltViewModel class Quoted"
val raw = """@AndroidEntryPoint @HiltAndroidApp @InstallIn"""
class Plain
SOURCE
expect_list

new_case release_java_comment java release <<'SOURCE'
package p.fixture;
/* Example delimiter: /* */
@HiltViewModel public final class Hidden {}
// End example: */
SOURCE
expect_unsupported 3 "source set release hors périmètre de la garde"

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

new_build_case custom_java_dir feature/example/build.gradle.kts <<'BUILD'
// A release source directory cannot be enumerated through src/<set>.
android.sourceSets["main"].java.srcDir("x")
BUILD
expect_unsupported 2 "répertoire de sources personnalisé non supporté"

new_build_case custom_groovy_dir feature/example/build.gradle <<'BUILD'
sourceSets.main.java.srcDirs = ['x']
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

new_build_case custom_build_logic build-logic/src/main/kotlin/Convention.kt <<'BUILD'
sourceSets.getByName("main").kotlin.directories += "x"
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

new_build_case custom_build_logic_script build-logic/build.gradle.kts <<'BUILD'
sourceSets.main.java.srcDir("x")
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

new_build_case custom_root_script build.gradle.kts <<'BUILD'
subprojects { sourceSets.main.java.srcDir("x") }
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

custom_accesses=(
  'java.srcDirs("x")'
  'kotlin.srcDir("x")'
  'kotlin.srcDirs("x")'
  'java.directories.add("x")'
  'kotlin.directories += "x"'
  'java.setSrcDirs(listOf("x"))'
  'getJava().srcDir("x")'
  'getKotlin().getSrcDirs().add("x")'
  'java { srcDirs("x") }'
  'kotlin { directories.add("x") }'
  'java.apply { srcDir("x") }'
  'with(kotlin) { srcDirs("x") }'
  'val extra = java; extra.srcDir("x")'
  'val extra = kotlin; extra.directories.add("x")'
)
for index in "${!custom_accesses[@]}"; do
  new_build_case "custom_access_$index" feature/example/build.gradle.kts <<<"${custom_accesses[$index]}"
  expect_unsupported 1 "répertoire de sources personnalisé non supporté"
done

new_build_case custom_multiline feature/example/build.gradle.kts <<'BUILD'
sourceSets["main"].java
    .srcDir("x")
BUILD
expect_unsupported 2 "répertoire de sources personnalisé non supporté"

new_build_case custom_comment_and_literal feature/example/build.gradle.kts <<'BUILD'
// java.srcDir("x")
/* kotlin.srcDirs("x") */
/* /* nested */ java.directories.add("x") */
val sample = "java.srcDir(\"x\")"
val raw = """kotlin.directories += "x""""
BUILD
expect_list "ViewModels : p.fixture.Real"

new_build_case assets_directories core/base/build.gradle.kts <<'BUILD'
android {
    sourceSets {
        named("test") {
            assets.directories.add("$projectDir/schemas")
        }
    }
}
BUILD
expect_list "ViewModels : p.fixture.Real"

for source_set in test testDebug androidTest androidTestProd testFixtures screenshotTest debug prodDebug; do
  new_build_case "custom_ignored_$source_set" build-logic/src/main/kotlin/Convention.kt <<BUILD
sourceSets.getByName("$source_set").kotlin.directories += "x"
BUILD
  expect_list "ViewModels : p.fixture.Real"
done

for source_set in main release prod devRelease unknown; do
  new_build_case "custom_selected_$source_set" feature/example/build.gradle.kts <<BUILD
sourceSets.getByName("$source_set").java.srcDir("x")
BUILD
  expect_unsupported 1 "répertoire de sources personnalisé non supporté"
done

new_build_case custom_dynamic_selector feature/example/build.gradle.kts <<'BUILD'
val name = "test"
sourceSets.getByName(name).java.srcDir("x")
BUILD
expect_unsupported 2 "répertoire de sources personnalisé non supporté"

new_build_case custom_interpolated_selector feature/example/build.gradle.kts <<'BUILD'
sourceSets.getByName("test${suffix}").kotlin.srcDirs("x")
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

new_build_case custom_other_receiver feature/example/build.gradle.kts <<'BUILD'
notSourceSets.getByName("test").kotlin.directories += "x"
BUILD
expect_unsupported 1 "répertoire de sources personnalisé non supporté"

new_build_case custom_ignored_then_release feature/example/build.gradle.kts <<'BUILD'
sourceSets.getByName("test").kotlin.directories += "test-helper"
sourceSets.getByName("release").kotlin.directories += "hidden"
BUILD
expect_unsupported 2 "répertoire de sources personnalisé non supporté"

new_build_case custom_groovy_comment feature/example/build.gradle <<'BUILD'
/* Example delimiter: /* */
sourceSets.main.java.srcDir('x')
// End example: */
BUILD
expect_unsupported 2 "répertoire de sources personnalisé non supporté"

new_build_case resources_directories feature/example/build.gradle.kts <<'BUILD'
assets { srcDir("x") }
resources.srcDirs("x")
BUILD
expect_list "ViewModels : p.fixture.Real"

new_build_case unincluded_custom_dir not/included/build.gradle.kts <<'BUILD'
java.srcDir("x")
BUILD
expect_list "ViewModels : p.fixture.Real"

echo "OK: $case_count cas de régression Hilt"
