#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SDK_VERSION="${EMBEDDED_SDK_VERSION:-0.1.0-SNAPSHOT}"
REPOSITORY="$ROOT_DIR/build/embedded-sdk-repository"
rm -rf "$REPOSITORY"

modules=(
  core:backend-spi
  core:runtime-core
  models:model-profile
  models:model-store
  models:model-catalog
  models:model-download
  models:model-install
  backends:llama-cpp
  observability:contracts
  transports:in-process
)
if grep -Fq '":integrations:android-embedded-sdk"' settings.gradle.kts; then
  modules+=(integrations:android-embedded-sdk)
fi

tasks=(":core:contracts:publishReleasePublicationToEmbeddedSdkRepository")
for module in "${modules[@]}"; do
  tasks+=(":${module}:publishEmbeddedReleasePublicationToEmbeddedSdkRepository")
done
./gradlew --no-configuration-cache \
  -PconsumerSdkVersion="$SDK_VERSION" \
  -PembeddedSdkVersion="$SDK_VERSION" \
  "${tasks[@]}"

for module in core:contracts "${modules[@]}"; do
  artifact="${module##*:}"
  case "$module" in
    core:contracts) artifact="core-contracts" ;;
    observability:contracts) artifact="observability-contracts" ;;
    integrations:android-embedded-sdk) artifact="embedded-android" ;;
  esac
  group_path="io/github/daniele21/localllm"
  if [[ "$module" == "observability:contracts" ]]; then group_path="$group_path/observability"; fi
  base="$REPOSITORY/$group_path/$artifact/$SDK_VERSION/$artifact-$SDK_VERSION"
  for ext in aar pom; do
    [[ -s "$base.$ext" ]] || {
      echo "Missing embedded SDK artifact: $base.$ext" >&2
      exit 1
    }
  done
done

if find "$REPOSITORY" -type f \( -name '*.gguf' -o -name '*.ggml' \) -print -quit | grep -q .; then
  echo "Model artifacts must not be packaged in the embedded SDK" >&2
  exit 1
fi

manifest="$REPOSITORY/build-manifest.txt"
{
  echo "embedded_sdk_version=$SDK_VERSION"
  echo "source_revision=$(git rev-parse HEAD 2>/dev/null || echo unavailable)"
  echo "artifact_count=$(find "$REPOSITORY/io/github/daniele21/localllm" -name '*.aar' | wc -l | tr -d ' ')"
} > "$manifest"

checksum="$REPOSITORY/SHA256SUMS"
find "$REPOSITORY/io/github/daniele21/localllm" -type f \
  \( -name '*.aar' -o -name '*.pom' -o -name '*.module' \) -print0 |
  sort -z |
  while IFS= read -r -d '' artifact; do
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum "$artifact"
    else
      shasum -a 256 "$artifact"
    fi
  done > "$checksum"

echo "Embedded Android publication staged: $SDK_VERSION (${#modules[@]} runtime modules)"
