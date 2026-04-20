#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${REDFACE_DOCKER_IMAGE:-ghcr.io/cirruslabs/android-sdk:36@sha256:f9b3ea9ed2b5fc9522adae82c7b4622ab7aa54207ef532c8e615a347dca08f31}"
CACHE_ROOT="${REDFACE_DOCKER_CACHE_DIR:-$ROOT_DIR/.gradle-user/docker-uid$(id -u)}"
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
