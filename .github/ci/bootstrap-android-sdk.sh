#!/usr/bin/env bash
# Official, checksum-pinned Android SDK bootstrap confined to Harnex's cache.
# Executed only by source-owned CI from a reviewed dev commit on a trusted Mac.
set -euo pipefail
umask 077

[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || {
  echo "::error::The isolated Harnex SDK bootstrap requires macOS ARM64" >&2
  exit 2
}
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || {
  echo "::error::RUNNER_TEMP must be a runner-owned directory" >&2
  exit 2
}
for tool in curl shasum unzip java; do
  command -v "$tool" >/dev/null || { echo "::error::Missing required tool: $tool" >&2; exit 2; }
done

cache="$HOME/Library/Caches/harnex-local-ci"
sdk="$cache/android-sdk"
have_platform() {
  [[ -d "$1/platforms/android-36" && -d "$1/build-tools/36.0.0" ]]
}
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
  "$HOME/Library/Android/sdk" "/opt/homebrew/share/android-commandlinetools" "$sdk"; do
  [[ -n "$candidate" ]] || continue
  if have_platform "$candidate"; then
    printf '%s\n' "$candidate"
    exit 0
  fi
done

# Nothing outside this project's cache or the exact runner's temporary area
# is created or removed. Multiple runner services may share the same HOME.
mkdir -p "$cache"
chmod 700 "$cache"
lock="$cache/.android-sdk-provision.lock"
acquired=false
for ((attempt=0; attempt<120; attempt++)); do
  if mkdir "$lock" 2>/dev/null; then acquired=true; break; fi
  if have_platform "$sdk"; then printf '%s\n' "$sdk"; exit 0; fi
  sleep 3
done
[[ "$acquired" == true ]] || {
  echo "::error::Harnex SDK installer lock busy; inspect $lock" >&2
  exit 2
}
work=""
cleanup() {
  result=$?
  trap - EXIT
  if [[ -n "$work" && -d "$work" ]]; then rm -rf -- "$work" || result=1; fi
  rmdir "$lock" || result=1
  exit "$result"
}
trap cleanup EXIT
work="$(mktemp -d "$RUNNER_TEMP/harnex-sdk.XXXXXXXX")"
mkdir -p "$sdk/cmdline-tools"
chmod 700 "$sdk"

manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$manager" ]]; then
  [[ ! -e "$sdk/cmdline-tools/latest" ]] || {
    echo "::error::Incomplete existing Harnex command-line tools; inspect $sdk/cmdline-tools/latest" >&2
    exit 2
  }
  # Pin the official macOS Apple Silicon bundle and published SHA-256.
  archive="$work/commandline-tools.zip"
  curl --fail --show-error --silent --location --retry 3 --connect-timeout 20 \
    --max-time 480 --proto '=https' --tlsv1.2 \
    "https://dl.google.com/android/repository/commandlinetools-mac_arm64-15859902_latest.zip" \
    --output "$archive"
  expected="835b62a26162b229b441d1f6d4680383815a270809eb33522c0d480fa5002c4e"
  actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || {
    echo "::error::Android command-line tools SHA-256 mismatch; nothing installed" >&2
    exit 2
  }
  unzip -q "$archive" -d "$work/unpacked"
  [[ -x "$work/unpacked/cmdline-tools/bin/sdkmanager" ]] || {
    echo "::error::Verified Android archive has an unexpected layout" >&2
    exit 2
  }
  mv "$work/unpacked/cmdline-tools" "$sdk/cmdline-tools/latest"
fi

# User explicitly requested SDK installation. Only SDK licenses requested by
# Google's pinned sdkmanager are accepted; no Homebrew, sudo, global paths,
# paid APIs, models, emulator images, or unrelated software are installed.
echo "Installing Android API 36, build-tools 36.0.0, and platform-tools in Harnex's isolated cache" >&2
set +e
yes | "$manager" --sdk_root="$sdk" --channel=0 \
  "platforms;android-36" "build-tools;36.0.0" "platform-tools" 1>&2
manager_status=${PIPESTATUS[1]}
set -e
[[ "$manager_status" -eq 0 ]] || {
  echo "::error::sdkmanager failed with exit code $manager_status; inspect SDK licenses/network" >&2
  exit 2
}
have_platform "$sdk" || {
  echo "::error::Android SDK installation incomplete: API 36 or build-tools 36.0.0 missing" >&2
  exit 2
}
"$manager" --sdk_root="$sdk" --list_installed 1>&2
printf '%s\n' "$sdk"
