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

title = find("Harnex · Local Chat")
ready = find("Model not ready · start local setup")
prepare = find("Prepare local model")
send = find("Send")
stop = find("Stop")
reset = find("New chat")

def rect(node):
    coords = list(map(int, re.findall(r"\d+", node["bounds"])))
    assert len(coords) == 4 and coords[2] > coords[0] and coords[3] > coords[1], node
    return coords

assert title["class"] == "android.widget.TextView", title
assert ready["class"] == "android.widget.TextView", ready
assert rect(title)[3] <= rect(ready)[1], "Status is obscured by the toolbar or Android system bars"

for label, button, enabled in [
    ("prepare", prepare, "true"),
    ("send", send, "false"),
    ("stop", stop, "false"),
    ("new chat", reset, "false"),
]:
    assert button["class"] == "android.widget.Button", (label, button)
    assert button["enabled"] == enabled, (label, button)
    if enabled == "true":
        assert button["clickable"] == "true", (label, button)
    print(f"{label}: enabled={enabled}, bounds={button['bounds']}")
    rect(button)
assert rect(ready)[3] <= rect(prepare)[1], "Setup action overlaps status"
assert rect(prepare)[3] <= rect(send)[1], "Composer overlaps setup action"
assert rect(send)[2] <= rect(stop)[0] and rect(stop)[2] <= rect(reset)[0], "Composer actions overlap"
editors = [n.attrib for n in nodes if n.get("class") == "android.widget.EditText"]
assert len(editors) == 1 and editors[0]["enabled"] == "false", editors
print("Embedded chat demo initial UI: visible model setup, disabled composer until ready, separate actions")
PY

cat > "$evidence/manifest.txt" <<EOF
source_revision=$(git rev-parse HEAD)
workflow_run=${GITHUB_RUN_ID:-local}
execution_environment=android-api35-x86_64-emulator
artifact_surface=standalone-embedded-smoke-apk-no-native-canary
claim=launch-model-setup-and-disabled-composer-only
physical-arm64-gguf-generation=not-tested
EOF
