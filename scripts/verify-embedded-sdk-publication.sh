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

# The integration candidate must be consumable without source checkout/build substitution.
if [[ -f samples/external-embedded-android/settings.gradle.kts ]]; then
  backend_aar="$REPOSITORY/io/github/daniele21/localllm/llama-cpp/$SDK_VERSION/llama-cpp-$SDK_VERSION.aar"
  if ! unzip -Z1 "$backend_aar" | grep -Fx 'jni/arm64-v8a/liblocal_llm_jni.so'; then
    echo "Published backend AAR is missing arm64-v8a JNI" >&2
    exit 1
  fi

  ./gradlew --no-configuration-cache -p samples/external-embedded-android \
    -PembeddedSdkVersion="$SDK_VERSION" \
    -PembeddedSdkRepositoryUrl="$REPOSITORY" \
    :app:assembleDebug

  if grep -R --line-number -E 'project\\(|includeBuild\\(|android-local-llm-harness' \
    samples/external-embedded-android --include='*.gradle.kts'; then
    echo "External Embedded SDK sample contains a forbidden source/build coupling" >&2
    exit 1
  fi

  apk="samples/external-embedded-android/app/build/outputs/apk/debug/app-debug.apk"
  if ! unzip -Z1 "$apk" | grep -Fx 'lib/arm64-v8a/liblocal_llm_jni.so'; then
    echo "External APK is missing the transitive native backend" >&2
    exit 1
  fi

  # Retain only a verified, independently built APK for physical-device smoke testing.
  # This is a CI/debug sample, not a release-signed Harnex Host or an SDK publication.
  smoke_dir="$ROOT_DIR/build/embedded-smoke-apk"
  rm -rf "$smoke_dir"
  mkdir -p "$smoke_dir"
  apk_name="harnex-embedded-smoke-arm64.apk"
  cp "$apk" "$smoke_dir/$apk_name"

  if command -v sha256sum >/dev/null 2>&1; then
    apk_sha256="$(sha256sum "$smoke_dir/$apk_name" | awk '{print $1}')"
  else
    apk_sha256="$(shasum -a 256 "$smoke_dir/$apk_name" | awk '{print $1}')"
  fi
  printf '%s  %s\n' "$apk_sha256" "$apk_name" > "$smoke_dir/SHA256SUMS"
  cat > "$smoke_dir/build-manifest.json" <<EOF
{
  "schema_version": 1,
  "project": "harnex",
  "platform": "android",
  "architecture": "arm64-v8a",
  "channel": "ci-smoke",
  "variant": "debug",
  "package_id": "io.github.daniele21.localllm.embedded.smoke",
  "source_revision": "$(git rev-parse HEAD)",
  "build_id": "${GITHUB_RUN_ID:-local}",
  "embedded_sdk_version": "$SDK_VERSION",
  "apk_file": "$apk_name",
  "apk_sha256": "$apk_sha256",
  "qualification": "build-only; representative physical ARM64/GGUF generation not yet verified"
}
EOF
  echo "Installable embedded smoke APK staged: $smoke_dir/$apk_name"
fi

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
