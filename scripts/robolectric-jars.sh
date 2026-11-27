#!/usr/bin/env bash
# Jars android-all de Robolectric en CI (#1681). Robolectric télécharge lui-même ses jars
# `android-all-instrumented` depuis Maven Central, hors de Gradle et donc hors du cache de
# `setup-gradle` : chaque runner neuf retirait ~800 Mo, et un téléchargement refusé ou coupé faisait
# tomber toutes les classes de la JVM de test concernée (`MavenArtifactFetcher.java:130`).
# Ce script précharge ces jars avec reprises et contrôle SHA-512, dans le dépôt Maven local que lisent
# les JVM de test ; le workflow met ce dossier en cache.
#
# Robolectric 4.17 (`MavenArtifactFetcher.fetchArtifact`) ne télécharge rien si le jar existe déjà
# dans le dépôt local. Il reste en ligne : un niveau d'API absent de la liste ci-dessous coûte un
# téléchargement par Robolectric, pas un échec. Le jar est donc déposé en dernier, par `mv`, après
# contrôle de son empreinte.
#
# Usage :
#   robolectric-jars.sh dir       affiche le dossier `android-all-instrumented` du dépôt local
#   robolectric-jars.sh prefetch  télécharge les jars manquants de la liste
# Env : ROBOLECTRIC_REPO_URL (défaut Maven Central), JAVA (défaut `java` du PATH, sinon $JAVA_HOME).
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
REPO_URL=${ROBOLECTRIC_REPO_URL:-https://repo1.maven.org/maven2}
ARTIFACT_PATH=org/robolectric/android-all-instrumented

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

# Télécharge $1 (chemin relatif au dépôt) vers $2, avec reprises (Retry-After respecté sur 429/503).
fetch() {
  local url="$REPO_URL/$1" out="$2" code
  code=$(curl --fail --location --no-progress-meter --show-error \
    --retry 6 --retry-delay 15 --retry-all-errors --connect-timeout 30 --max-time 900 \
    --write-out '%{http_code}' --output "$out" "$url") || {
    echo "::error::échec du téléchargement de $url (HTTP ${code:-?})" >&2
    return 1
  }
  echo "  $1 : HTTP $code, $(stat -c %s "$out") octets"
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
  local repo dir version base tmp
  repo=$(local_repo)
  for version in "${VERSIONS[@]}"; do
    dir="$repo/$ARTIFACT_PATH/$version"
    base="android-all-instrumented-$version"
    if [[ -f "$dir/$base.jar" ]]; then
      echo "$version : déjà présent"
      continue
    fi
    echo "$version : téléchargement"
    mkdir -p "$dir"
    tmp=$(mktemp -d "$dir/.prefetch.XXXXXX")
    TMP_DIR=$tmp
    fetch "$ARTIFACT_PATH/$version/$base.pom" "$tmp/$base.pom"
    fetch "$ARTIFACT_PATH/$version/$base.pom.sha512" "$tmp/$base.pom.sha512"
    fetch "$ARTIFACT_PATH/$version/$base.jar.sha512" "$tmp/$base.jar.sha512"
    fetch "$ARTIFACT_PATH/$version/$base.jar" "$tmp/$base.jar"
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
