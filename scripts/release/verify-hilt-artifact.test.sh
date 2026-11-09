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
  source_file="$case_root/feature/example/src/main/Example.${2:-kt}"
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

echo "OK: $case_count cas de régression Hilt"
