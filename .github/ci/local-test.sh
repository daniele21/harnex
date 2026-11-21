#!/usr/bin/env bash
# Source-owned, bounded Mac ARM64 validation. Does not replace FULL hosted gates.
# No private models, inference content, paid APIs, signing or emulator sessions.
set -euo pipefail
umask 077

[[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] || {
  echo "Harnex local validation requires a macOS ARM64 runner" >&2
  exit 2
}
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || {
  echo "Missing GitHub runner temporary directory" >&2
  exit 2
}
[[ -n "${GITHUB_WORKSPACE:-}" && -d "$GITHUB_WORKSPACE" ]] || {
  echo "Missing owned GitHub checkout" >&2
  exit 2
}
[[ "$(pwd -P)" == "$(cd "$GITHUB_WORKSPACE" && pwd -P)" ]] || {
  echo "Will not clean or compile outside the owned runner checkout" >&2
  exit 2
}
command -v java >/dev/null || { echo "Java/JDK 17 is required on the Mac runner" >&2; exit 2; }
command -v python3 >/dev/null || { echo "Python 3 is required on the Mac runner" >&2; exit 2; }
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[[ -d "$sdk/platforms/android-36" && -d "$sdk/build-tools/36.0.0" ]] || {
  echo "Android SDK 36 platform/build-tools not installed at $sdk" >&2
  exit 2
}
export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"

# Keep Gradle's reusable cache project-scoped; all job-temporary data is deleted.
export GRADLE_USER_HOME="$HOME/Library/Caches/harnex-local-ci/gradle"
mkdir -p "$GRADLE_USER_HOME"
chmod 700 "$GRADLE_USER_HOME"
scratch="$(mktemp -d "$RUNNER_TEMP/harnex-ci.XXXXXXXX")"
# Clean only generated/ignored files within this exact owned checkout, not
# other runner workspaces, repositories or the deliberate project Gradle cache.
cleanup() {
  result=$?
  trap - EXIT
  rm -rf -- "$scratch" || result=1
  git clean -ffdX -- . >/dev/null || result=1
  exit "$result"
}
trap cleanup EXIT

python3 scripts/verify_no_tracked_generated_artifacts.py
python3 scripts/verify_repository.py
python3 scripts/verify_operations.py
python3 scripts/verify_e2e.py
python3 scripts/verify_agent_context.py
python3 scripts/test_detect_ci_scope.py
python3 scripts/test_gradle_module_inventory.py
python3 scripts/test_local_ci_dispatch_contract.py

# Explicit scoped Android unit tests: benchmark Mac cache/time without claiming
# all-module lint, JNI packaging, emulator E2E or release validation parity.
bash ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
  --project-cache-dir="$scratch/gradle-project" \
  :models:model-download:testDebugUnitTest \
  :integrations:android-embedded-sdk:testDebugUnitTest

echo "Harnex scoped Mac smoke passed (NOT FULL Android/release validation)."
