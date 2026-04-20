#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${REDFACE_DOCKER_IMAGE:-ghcr.io/cirruslabs/android-sdk:36}"
CACHE_ROOT="${REDFACE_DOCKER_CACHE_DIR:-$ROOT_DIR/.gradle-user/docker}"
GRADLE_CACHE_DIR="$CACHE_ROOT/gradle"
ANDROID_CACHE_DIR="$CACHE_ROOT/android"

mkdir -p "$GRADLE_CACHE_DIR" "$ANDROID_CACHE_DIR"

if [ "$#" -eq 0 ]; then
  set -- ./gradlew :app:assembleDebug
fi

TTY_ARGS=()
if [ -t 0 ] && [ -t 1 ]; then
  TTY_ARGS=(-it)
fi

exec docker run --rm \
  "${TTY_ARGS[@]}" \
  --security-opt label=disable \
  -v "$ROOT_DIR:/workspace" \
  -v "$GRADLE_CACHE_DIR:/root/.gradle" \
  -v "$ANDROID_CACHE_DIR:/root/.android" \
  -w /workspace \
  "$IMAGE" \
  "$@"
