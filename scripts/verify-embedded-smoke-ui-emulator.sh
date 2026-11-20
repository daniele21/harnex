#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
apk="$ROOT/samples/external-embedded-android/app/build/outputs/apk/debug/app-debug.apk"
evidence="$ROOT/build/embedded-smoke-ui-evidence"
rm -rf "$evidence"
mkdir -p "$evidence"
[[ -s "$apk" ]] || { echo "External embedded APK not found" >&2; exit 1; }

# x86_64 emulator checks only the visible controls; ARM64 native/real GGUF
# remain separate physical-device evidence. Do not mistake this for inference.
tmp="$(mktemp -d)"
export ANDROID_AVD_HOME="$tmp/avd" ANDROID_USER_HOME="$tmp/user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
cleanup() {
  adb -s emulator-5554 emu kill >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

cp "$apk" "$tmp/without-native-unaligned.apk"
zip -dq "$tmp/without-native-unaligned.apk" 'lib/*'
if unzip -Z1 "$tmp/without-native-unaligned.apk" | grep -q '^lib/'; then
  echo "UI canary still contains native libraries" >&2
  exit 1
fi
tools="$ANDROID_HOME/build-tools/36.0.0"
keytool -genkeypair -keystore "$tmp/ui-canary.p12" -storetype PKCS12 \
  -storepass android -keypass android -alias smoke-ui \
  -dname "CN=Harnex CI Smoke UI, O=Harnex, C=US" \
  -keyalg RSA -keysize 2048 -validity 1 -noprompt >/dev/null 2>&1
"$tools/zipalign" -f 4 "$tmp/without-native-unaligned.apk" "$tmp/ui-canary.apk"
"$tools/apksigner" sign --ks "$tmp/ui-canary.p12" --ks-key-alias smoke-ui \
  --ks-pass pass:android --key-pass pass:android "$tmp/ui-canary.apk"
"$tools/apksigner" verify "$tmp/ui-canary.apk"

package="io.github.daniele21.localllm.embedded.smoke"
activity="io.github.daniele21.localllm.externalembedded.EmbeddedSmokeActivity"
"$tools/aapt" dump badging "$tmp/ui-canary.apk" | grep -F "package: name='$package'" >/dev/null
if [[ -e /dev/kvm ]]; then sudo chmod 666 /dev/kvm; fi
echo no | avdmanager create avd --force --name embedded-smoke-ui-api35 \
  --package 'system-images;android-35;google_apis;x86_64' --device pixel_6 >/dev/null
nohup "$ANDROID_HOME/emulator/emulator" \
  -avd embedded-smoke-ui-api35 -port 5554 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot \
  > "$evidence/emulator.log" 2>&1 &
timeout 180 adb -s emulator-5554 wait-for-device
for attempt in $(seq 1 90); do
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
    break
  fi
  [[ "$attempt" -lt 90 ]] || { echo "Emulator boot timeout" >&2; exit 1; }
  sleep 2
done

adb -s emulator-5554 install -r -t --no-streaming "$tmp/ui-canary.apk" > "$evidence/install.txt"
adb -s emulator-5554 shell am start -W -n "$package/$activity" > "$evidence/start.txt"
sleep 3
adb -s emulator-5554 shell uiautomator dump /sdcard/embedded-smoke.xml >/dev/null
adb -s emulator-5554 pull /sdcard/embedded-smoke.xml "$evidence/window.xml" >/dev/null
adb -s emulator-5554 exec-out screencap -p > "$evidence/screenshot.png"
adb -s emulator-5554 shell pidof "$package" > "$evidence/process.txt"
[[ -s "$evidence/process.txt" ]] || { echo "Smoke Activity process exited" >&2; exit 1; }

python3 - "$evidence/window.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
nodes = list(root.iter("node"))
def find(text):
    matches = [n.attrib for n in nodes if n.get("text") == text]
    assert len(matches) == 1, f"Expected exactly one visible {text!r}, got {len(matches)}"
    return matches[0]

ready = find("Ready to run the embedded model smoke test")
run = find("Download / Install / Generate")
cancel = find("Cancel")
assert ready["class"] == "android.widget.TextView", ready
for name, button in [("run", run), ("cancel", cancel)]:
    assert button["class"] == "android.widget.Button", (name, button)
    assert button["clickable"] == "true" and button["enabled"] == "true", (name, button)
    coords = list(map(int, re.findall(r"\d+", button["bounds"])))
    assert len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1], (name, button)
    print(f"{name} visible: {button['bounds']}")
run_rect = list(map(int, re.findall(r"\d+", run["bounds"])))
cancel_rect = list(map(int, re.findall(r"\d+", cancel["bounds"])))
assert run_rect[3] <= cancel_rect[1], "Smoke actions overlap"
print("Embedded smoke controls are visible, clickable, and non-overlapping on Android API 35 emulator")
PY

cat > "$evidence/manifest.txt" <<EOF
source_revision=$(git rev-parse HEAD)
workflow_run=${GITHUB_RUN_ID:-local}
execution_environment=android-api35-x86_64-emulator
artifact_surface=standalone-embedded-smoke-apk-no-native-canary
claim=launch-and-controls-only
physical-arm64-gguf-generation=not-tested
EOF
