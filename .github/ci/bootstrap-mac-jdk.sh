#!/usr/bin/env bash
# Temurin 17 for the Harnex Mac lane; no sudo or global Java installation.
# Official Adoptium asset and digest are fixed, not user-controlled inputs.
set -euo pipefail
umask 077
[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || {
  echo "::error::Harnex JDK bootstrap is macOS ARM64 only" >&2
  exit 2
}
[[ -n "${RUNNER_TEMP:-}" && -d "$RUNNER_TEMP" ]] || {
  echo "::error::Missing runner-owned temporary directory" >&2
  exit 2
}
cache="$HOME/Library/Caches/harnex-local-ci"
dest="$cache/jdk/temurin-17.0.20.1_1"
usable_jdk() {
  [[ -n "$1" && -x "$1/bin/java" ]] || return 1
  "$1/bin/java" -version 2>&1 | grep -Eq 'version "17[.]'
}
candidate_from_system=""
if [[ -x /usr/libexec/java_home ]]; then
  candidate_from_system="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
for candidate in \
  "${JAVA_HOME:-}" \
  "$candidate_from_system" \
  "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
  "/opt/homebrew/opt/openjdk@17" \
  "/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home" \
  "$dest/Contents/Home"; do
  if usable_jdk "$candidate"; then
    printf '%s\n' "$candidate"
    exit 0
  fi
done
for tool in curl shasum tar; do
  command -v "$tool" >/dev/null || { echo "::error::Missing $tool" >&2; exit 2; }
done
mkdir -p "$cache/jdk"
chmod 700 "$cache"
lock="$cache/.jdk-17-provision.lock"
acquired=false
for ((attempt=0; attempt<120; attempt++)); do
  if mkdir "$lock" 2>/dev/null; then acquired=true; break; fi
  if usable_jdk "$dest/Contents/Home"; then
    printf '%s\n' "$dest/Contents/Home"
    exit 0
  fi
  sleep 3
done
[[ "$acquired" == true ]] || {
  echo "::error::JDK installer lock busy; inspect $lock" >&2
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
[[ ! -e "$dest" ]] || {
  echo "::error::Incomplete pre-existing Harnex JDK bundle; inspect $dest" >&2
  exit 2
}
work="$(mktemp -d "$RUNNER_TEMP/harnex-jdk.XXXXXXXX")"
archive="$work/temurin-jdk17.tar.gz"
curl --fail --show-error --silent --location --retry 3 --connect-timeout 20 \
  --max-time 600 --proto '=https' --proto-redir '=https' --tlsv1.2 \
  "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.20.1_1.tar.gz" \
  --output "$archive"
expected="196d13ba5f10414bef7f6a05a9b3f00edacb18ebacef2b99485db9e2ee18f0e8"
actual="$(shasum -a 256 "$archive" | awk '{print $1}')"
[[ "$actual" == "$expected" ]] || {
  echo "::error::Temurin JDK 17 SHA-256 mismatch; no Java installed" >&2
  exit 2
}
mkdir -p "$work/unpacked"
tar -xzf "$archive" -C "$work/unpacked"
bundle=""
for java_file in "$work/unpacked"/*/Contents/Home/bin/java; do
  [[ -x "$java_file" ]] || continue
  bundle="${java_file%/Contents/Home/bin/java}"
  break
done
[[ -n "$bundle" ]] || {
  echo "::error::Verified Temurin JDK archive has unexpected macOS layout" >&2
  exit 2
}
usable_jdk "$bundle/Contents/Home" || {
  echo "::error::Downloaded Temurin binary is not a usable Java 17" >&2
  exit 2
}
mv "$bundle" "$dest"
printf '%s\n' "$dest/Contents/Home"
