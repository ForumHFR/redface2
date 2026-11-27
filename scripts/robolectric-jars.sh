#!/usr/bin/env bash
# Jars android-all de Robolectric en CI (#1681). Robolectric télécharge lui-même ses jars
# `android-all-instrumented` depuis Maven Central, hors de Gradle et donc hors du cache de
# `setup-gradle` : chaque runner neuf retirait ~800 Mo, et un téléchargement refusé ou coupé faisait
# tomber toutes les classes de la JVM de test concernée (`MavenArtifactFetcher.java:130`).
# Le 27/11/2026, Maven Central répondait 429 aux runners GitHub sur ces jars (les pom et .sha512
# passaient), avec un Retry-After de ~17 h.
#
# Ce script précharge ces jars dans le dépôt Maven local que lisent les JVM de test ; le workflow met
# ce dossier en cache. Les empreintes SHA-512 viennent d'abord de Maven Central ; les jars et pom
# d'abord du miroir Google de Maven Central, Central en repli. Reprises bornées, gérées ici : curl ne
# suit pas le Retry-After (il bloquerait le job jusqu'à son délai maximal).
#
# Robolectric 4.17 (`MavenArtifactFetcher.fetchArtifact`) ne télécharge rien si le jar existe déjà
# dans le dépôt local. Le jar est donc déposé en dernier, par `mv`, après contrôle de son empreinte.
# Un niveau d'API absent de la liste ci-dessous serait téléchargé par Robolectric lui-même, depuis
# Maven Central, sans ces protections.
#
# Usage :
#   robolectric-jars.sh dir       affiche le dossier `android-all-instrumented` du dépôt local
#   robolectric-jars.sh prefetch  télécharge les jars manquants de la liste
# Env : ROBOLECTRIC_CHECKSUM_REPOS, ROBOLECTRIC_ARTIFACT_REPOS (URL séparées par des espaces, dans
# l'ordre d'essai), JAVA (défaut `java` du PATH, sinon $JAVA_HOME/bin/java).
set -euo pipefail

# Versions `android-all-instrumented` des tests (Robolectric 4.17, préinstrumentation i7,
# `DefaultSdkProvider`) : SDK 29, 30, 32, 33, 34 (@Config et robolectric.properties), 36 (classes de
# `:app` sans @Config, plafond de Robolectric). À revoir à chaque montée de Robolectric : la clé du
# cache suit le contenu de ce script.
VERSIONS=(
  10-robolectric-5803371-i7
  11-robolectric-6757853-i7
  12.1-robolectric-8229987-i7
  13-robolectric-9030017-i7
  14-robolectric-10818077-i7
  16-robolectric-13921718-i7
)
CENTRAL=https://repo1.maven.org/maven2
GOOGLE_MIRROR=https://maven-central.storage-download.googleapis.com/maven2
read -r -a CHECKSUM_REPOS <<< "${ROBOLECTRIC_CHECKSUM_REPOS:-$CENTRAL $GOOGLE_MIRROR}"
read -r -a ARTIFACT_REPOS <<< "${ROBOLECTRIC_ARTIFACT_REPOS:-$GOOGLE_MIRROR $CENTRAL}"
ARTIFACT_PATH=org/robolectric/android-all-instrumented
ROUNDS=3

java_bin() {
  if [[ -n "${JAVA:-}" ]]; then echo "$JAVA"
  elif command -v java >/dev/null 2>&1; then echo java
  elif [[ -n "${JAVA_HOME:-}" ]]; then echo "$JAVA_HOME/bin/java"
  else echo "::error::java introuvable (ni JAVA, ni PATH, ni JAVA_HOME)" >&2; exit 1
  fi
}

# Même résolution que `MavenDependencyResolver.getLocalRepositoryDir` : `maven.repo.local` (jamais
# posé par ce build), puis <localRepository> de ~/.m2/settings.xml, puis ~/.m2/repository, où ~ est
# le `user.home` de la JVM, pas $HOME (un job `container:` pose HOME=/github/home, la JVM lit passwd).
local_repo() {
  local home
  home=$("$(java_bin)" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *user\.home = //p')
  if [[ -z "$home" ]]; then
    echo "::error::user.home de la JVM illisible" >&2; exit 1
  fi
  if [[ -f "$home/.m2/settings.xml" ]] && grep -q '<localRepository>' "$home/.m2/settings.xml"; then
    echo "::error::$home/.m2/settings.xml redéfinit localRepository : non géré par ce script" >&2
    exit 1
  fi
  echo "$home/.m2/repository"
}

# fetch <chemin relatif> <sortie> <dépôt>... : essaie chaque dépôt dans l'ordre, sur ROUNDS tours
# espacés de 20 s, 40 s… Chaque échec est journalisé avec son code HTTP (000 = pas de réponse).
fetch() {
  local path="$1" out="$2" round base code
  shift 2
  for ((round = 1; round <= ROUNDS; round++)); do
    for base in "$@"; do
      if code=$(curl --fail --location --no-progress-meter --show-error \
        --connect-timeout 30 --max-time 900 \
        --write-out '%{http_code}' --output "$out" "$base/$path"); then
        echo "  $(basename "$path") : HTTP $code, $(stat -c %s "$out") octets, $base"
        return 0
      fi
      echo "  $(basename "$path") : HTTP ${code:-000}, $base (tour $round/$ROUNDS)" >&2
    done
    ((round < ROUNDS)) && sleep $((round * 20))
  done
  echo "::error::échec du téléchargement de $path depuis : $*" >&2
  return 1
}

# Contrôle $1 contre l'empreinte hexadécimale contenue dans $2.
check_sha512() {
  local expected actual
  expected=$(tr -d '[:space:]' < "$2")
  actual=$(sha512sum "$1" | cut -d' ' -f1)
  if [[ "$expected" != "$actual" ]]; then
    echo "::error::SHA-512 incorrect pour $1" >&2
    return 1
  fi
}

TMP_DIR=
cleanup() { [[ -n "$TMP_DIR" ]] && rm -rf "$TMP_DIR"; return 0; }
trap cleanup EXIT

prefetch() {
  local repo dir version base rel tmp
  repo=$(local_repo)
  for version in "${VERSIONS[@]}"; do
    dir="$repo/$ARTIFACT_PATH/$version"
    base="android-all-instrumented-$version"
    rel="$ARTIFACT_PATH/$version/$base"
    if [[ -f "$dir/$base.jar" ]]; then
      echo "$version : déjà présent"
      continue
    fi
    echo "$version : téléchargement"
    mkdir -p "$dir"
    tmp=$(mktemp -d "$dir/.prefetch.XXXXXX")
    TMP_DIR=$tmp
    fetch "$rel.pom.sha512" "$tmp/$base.pom.sha512" "${CHECKSUM_REPOS[@]}"
    fetch "$rel.jar.sha512" "$tmp/$base.jar.sha512" "${CHECKSUM_REPOS[@]}"
    fetch "$rel.pom" "$tmp/$base.pom" "${ARTIFACT_REPOS[@]}"
    fetch "$rel.jar" "$tmp/$base.jar" "${ARTIFACT_REPOS[@]}"
    check_sha512 "$tmp/$base.pom" "$tmp/$base.pom.sha512"
    check_sha512 "$tmp/$base.jar" "$tmp/$base.jar.sha512"
    mv "$tmp/$base.pom" "$tmp/$base.pom.sha512" "$tmp/$base.jar.sha512" "$dir/"
    mv "$tmp/$base.jar" "$dir/"
    rmdir "$tmp"
    TMP_DIR=
  done
}

case "${1:-}" in
  dir) echo "$(local_repo)/$ARTIFACT_PATH" ;;
  prefetch) prefetch ;;
  *) echo "usage: $0 dir|prefetch" >&2; exit 2 ;;
esac
