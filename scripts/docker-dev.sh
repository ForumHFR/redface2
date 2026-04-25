#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${REDFACE_DOCKER_IMAGE:-ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31}"
CACHE_ROOT="${REDFACE_DOCKER_CACHE_DIR:-$ROOT_DIR/.gradle-user/docker-uid$(id -u)}"
GRADLE_CACHE_DIR="$CACHE_ROOT/gradle"
ANDROID_CACHE_DIR="$CACHE_ROOT/android"
PROJECT_CACHE_DIR_INSIDE="/workspace/.gradle-user/$(basename "$CACHE_ROOT")/project-cache"

mkdir -p "$GRADLE_CACHE_DIR" "$ANDROID_CACHE_DIR"

# Redirect Gradle's per-project cache (rootProject/.gradle/) outside the
# repo. Without this, podman --userns keep-id remaps the container UID
# into a subordinate UID range, and files created by Gradle in
# <repo>/.gradle/ end up owned by an UNKNOWN UID host-side, blocking
# `gradlew` invocations outside Docker, IDE indexing, and basic cleanup.
#
# Why we DON'T redirect per-module build/ outputs (<module>/build/) the
# same way: Android Studio and IntelliJ rely on the convention path for
# generated sources (Hilt, KSP, R.java) and for AGP test-discovery. Moving
# build/ outside the repo breaks IDE indexing, run-from-IDE, and the AGP
# Gradle import. Outputs come back as UNKNOWN-owned only when the build
# was run via this docker-dev.sh script and then the user wants to touch
# them outside the container — typical recovery is a one-shot:
#   podman unshare rm -rf build core/*/build feature/*/build app/build
# That tradeoff is intentional and was decided in PR #76. If the IDE
# friction ever exceeds the cleanup friction, the right move is to set
# `layout.buildDirectory` in build-logic/, not to extend this script.
inject_project_cache_dir() {
  local entrypoint="$1"
  shift
  # Don't add the flag twice if the caller already provided one (either form:
  # --project-cache-dir <path> or --project-cache-dir=<path>).
  for arg in "$@"; do
    case "$arg" in
      --project-cache-dir|--project-cache-dir=*) printf '%s\0' "$entrypoint" "$@"; return ;;
    esac
  done
  # NUL-delimited so args containing newlines survive mapfile reassembly.
  printf '%s\0' "$entrypoint" "--project-cache-dir=$PROJECT_CACHE_DIR_INSIDE" "$@"
}

if [ "$#" -eq 0 ]; then
  set -- ./gradlew :app:assembleDebug
fi

case "$1" in
  ./gradlew|gradlew|/workspace/gradlew)
    mapfile -d '' -t WRAPPED_ARGS < <(inject_project_cache_dir "$@")
    set -- "${WRAPPED_ARGS[@]}"
    ;;
esac

TTY_ARGS=()
if [ -t 0 ] && [ -t 1 ]; then
  TTY_ARGS=(-it)
fi

USERNS_ARGS=()
if docker info --format '{{.Host.RemoteSocket.Path}}' 2>/dev/null | grep -qi 'podman'; then
  USERNS_ARGS=(--userns keep-id)
fi

exec docker run --rm \
  "${TTY_ARGS[@]}" \
  "${USERNS_ARGS[@]}" \
  --security-opt label=disable \
  --user "$(id -u):$(id -g)" \
  -e HOME=/workspace/.gradle-user/$(basename "$CACHE_ROOT")/home \
  -e GRADLE_USER_HOME=/workspace/.gradle-user/$(basename "$CACHE_ROOT")/gradle \
  -e ANDROID_USER_HOME=/workspace/.gradle-user/$(basename "$CACHE_ROOT")/android \
  -v "$ROOT_DIR:/workspace" \
  -w /workspace \
  "$IMAGE" \
  "$@"
