#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="io.github.daniele21.localllm.phonetest"
MAIN_ACTIVITY="$APP_ID/.MainActivity"
EXPECTED_2B_SHA="0af96165ea615bea39a04118d63f0b6d35908aea850ee4a51aa6151d851b8b35"
EXPECTED_4B_SHA="b252c5610a42ca82d20fe2a12813e9d069eed89292907e26c783eeb0bc961bc7"
QUANTIZATION="UD-Q4_K_XL"
DEVICE=""
OUTPUT_DIR="$ROOT_DIR/build/q35-ud-q4-k-xl-physical-acceptance"
ADB_BIN="${ADB:-adb}"

usage() {
    cat <<'EOF'
Usage:
  bash scripts/run-qwen35-ud-q4-k-xl-physical-acceptance.sh [options]

Coordinates physical acceptance for the exact Qwen3.5 2B and 4B UD-Q4_K_XL
models already installed in Harnex on the connected Android device.

The script deliberately does NOT:
  - require GGUF paths on the Mac;
  - copy models to another app;
  - rebuild or reinstall Harnex;
  - expose a test-only control endpoint in the Harnex release app.

Instead, Harnex performs its existing host-owned Physical-device validation
against its selected ModelStore artifact. After each run, this script captures
the privacy-safe report from the visible UI through ADB accessibility metadata,
checks the exact model digest/quantization, and stores report + screenshot +
device provenance on the Mac.

Options:
  --device SERIAL       ADB device serial (alias: --serial).
  --output-dir PATH     Evidence root.
  --help                Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device|--serial)
            DEVICE="${2:-}"
            shift 2
            ;;
        --output-dir)
            OUTPUT_DIR="${2:-}"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

if ! command -v "$ADB_BIN" >/dev/null 2>&1; then
    echo "adb is required" >&2
    exit 2
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "python3 is required" >&2
    exit 2
fi

ADB_CMD=("$ADB_BIN")
if [[ -n "$DEVICE" ]]; then
    ADB_CMD+=("-s" "$DEVICE")
fi

adb_shell() {
    "${ADB_CMD[@]}" shell "$@"
}

"${ADB_CMD[@]}" get-state >/dev/null

if ! adb_shell pm path "$APP_ID" 2>/dev/null | grep -q '^package:'; then
    echo "Harnex ($APP_ID) is not installed on the selected device." >&2
    echo "Install/open the Harnex build that already owns the 2B and 4B models, then rerun." >&2
    exit 2
fi

DEVICE_MANUFACTURER="$(adb_shell getprop ro.product.manufacturer | tr -d '\r')"
DEVICE_MODEL="$(adb_shell getprop ro.product.model | tr -d '\r')"
DEVICE_RELEASE="$(adb_shell getprop ro.build.version.release | tr -d '\r')"
DEVICE_SDK="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_ABI="$(adb_shell getprop ro.product.cpu.abi | tr -d '\r')"
DEVICE_SERIAL="${DEVICE:-$("${ADB_CMD[@]}" get-serialno | tr -d '\r')}"
DEVICE_RAM_KB="$(adb_shell cat /proc/meminfo | tr -d '\r' | awk '/^MemTotal:/ {print $2; exit}')"

if [[ "$DEVICE_ABI" != arm64-v8a* ]]; then
    echo "Physical acceptance requires an arm64-v8a device; found: $DEVICE_ABI" >&2
    exit 2
fi

PACKAGE_DUMP="$(adb_shell dumpsys package "$APP_ID" | tr -d '\r')"
APP_VERSION_NAME="$(printf '%s\n' "$PACKAGE_DUMP" | sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)"
APP_VERSION_CODE="$(printf '%s\n' "$PACKAGE_DUMP" | sed -n 's/^[[:space:]]*versionCode=\([0-9][0-9]*\).*/\1/p' | head -n 1)"

cd "$ROOT_DIR"
RUNNER_COMMIT="$(git rev-parse HEAD 2>/dev/null || printf 'unknown')"
TRACKED_STATUS="$(git status --short --untracked-files=no 2>/dev/null || true)"
RUN_STAMP="$(date -u '+%Y%m%dT%H%M%SZ')"
SAFE_MODEL="$(printf '%s' "$DEVICE_MODEL" | tr ' /:' '___')"
RUN_DIR="$OUTPUT_DIR/$SAFE_MODEL/$RUN_STAMP"
mkdir -p "$RUN_DIR"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/harnex-q35-acceptance.XXXXXX")"
cleanup() {
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

{
    echo "deviceSerial=$DEVICE_SERIAL"
    echo "manufacturer=$DEVICE_MANUFACTURER"
    echo "model=$DEVICE_MODEL"
    echo "android=$DEVICE_RELEASE"
    echo "api=$DEVICE_SDK"
    echo "abi=$DEVICE_ABI"
    echo "ramKb=$DEVICE_RAM_KB"
    echo "harnexVersionName=${APP_VERSION_NAME:-unknown}"
    echo "harnexVersionCode=${APP_VERSION_CODE:-unknown}"
    echo "runnerCommit=$RUNNER_COMMIT"
    if [[ -n "$TRACKED_STATUS" ]]; then
        echo "runnerWorktree=dirty"
        printf '%s\n' "$TRACKED_STATUS" | sed 's/^/runnerTrackedChange=/'
    else
        echo "runnerWorktree=clean"
    fi
} > "$RUN_DIR/device-and-runner.txt"

extract_visible_report() {
    xml_file="$1"
    report_file="$2"
    python3 - "$xml_file" "$report_file" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

xml_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
root = ET.parse(xml_path).getroot()
texts = [node.attrib.get("text", "") for node in root.iter()]
texts = [text for text in texts if text]

prefix = "LOCAL_LLM_PHONE_TEST result="
candidates = [text for text in texts if prefix in text]
if candidates:
    report = max(candidates, key=len)
else:
    combined = "\n".join(texts)
    start = combined.find(prefix)
    if start < 0:
        raise SystemExit(
            "Could not find LOCAL_LLM_PHONE_TEST in the visible UI. "
            "Keep Diagnostics > Validation with the report visible, then retry."
        )
    report = combined[start:]

report = report.replace("\r\n", "\n").replace("\r", "\n").strip()
report_path.write_text(report + "\n", encoding="utf-8")
PY
}

capture_report() {
    tier="$1"
    expected_sha="$2"
    tier_dir="$RUN_DIR/$tier"
    xml_remote="/sdcard/harnex-${tier}-validation.xml"
    xml_local="$tier_dir/ui.xml"
    report_file="$tier_dir/report.txt"
    screenshot_file="$tier_dir/screenshot.png"

    mkdir -p "$tier_dir"

    echo
    echo "=== Qwen3.5 $tier $QUANTIZATION ==="
    echo "Sul telefono:"
    echo "  1. Harnex > Models: seleziona Qwen 3.5 $tier $QUANTIZATION già installato."
    echo "  2. Harnex > Diagnostics > Validation."
    echo "  3. Tocca 'Run full validation'."
    echo "  4. Attendi 'Validation completed' e lascia visibile il report."
    echo
    printf "Quando il report è visibile, premi INVIO qui... "
    IFS= read -r _

    "${ADB_CMD[@]}" exec-out screencap -p > "$screenshot_file"
    adb_shell uiautomator dump "$xml_remote" >/dev/null
    "${ADB_CMD[@]}" pull "$xml_remote" "$xml_local" >/dev/null
    adb_shell rm -f "$xml_remote" >/dev/null 2>&1 || true

    if ! extract_visible_report "$xml_local" "$report_file"; then
        echo "Impossibile estrarre il report $tier dalla UI." >&2
        echo "Screenshot salvato in: $screenshot_file" >&2
        exit 1
    fi

    if ! grep -Fq 'LOCAL_LLM_PHONE_TEST result=PASS' "$report_file"; then
        echo "$tier validation did not report PASS:" >&2
        cat "$report_file" >&2
        exit 1
    fi
    if ! grep -Fq "modelDigest=$expected_sha" "$report_file"; then
        echo "$tier report is not bound to the expected UD-Q4_K_XL artifact digest." >&2
        echo "Expected: $expected_sha" >&2
        cat "$report_file" >&2
        exit 1
    fi
    if ! grep -Fq 'architecture=qwen35' "$report_file"; then
        echo "$tier report does not identify qwen35 architecture." >&2
        exit 1
    fi
    if ! grep -Fq "quantization=$QUANTIZATION" "$report_file"; then
        echo "$tier report does not identify quantization=$QUANTIZATION." >&2
        exit 1
    fi
    for marker in \
        'ttftMs=' \
        'totalMs=' \
        'decodeTokensPerSecond=' \
        'cancellation=cancelled' \
        'pssSamplesKb=' \
        'pssGrowthKb=' \
        'thermalStart=' \
        'thermalEnd='; do
        if ! grep -Fq "$marker" "$report_file"; then
            echo "$tier report is missing required evidence marker: $marker" >&2
            exit 1
        fi
    done

    echo "$tier PASS — report: $report_file"
}

# Launch only. We intentionally do not rebuild/reinstall the app because the Play
# install owns the existing private ModelStore artifacts and must remain intact.
adb_shell am start -n "$MAIN_ACTIVITY" >/dev/null

cat <<EOF
Harnex physical acceptance
Device: $DEVICE_MANUFACTURER $DEVICE_MODEL · Android $DEVICE_RELEASE · $DEVICE_ABI
Installed Harnex: ${APP_VERSION_NAME:-unknown} (${APP_VERSION_CODE:-unknown})
Evidence directory: $RUN_DIR

No GGUF will be read from or copied from the Mac.
Harnex itself will execute its existing host-owned Physical-device validation.
EOF

capture_report "2B" "$EXPECTED_2B_SHA"
capture_report "4B" "$EXPECTED_4B_SHA"

python3 - \
    "$RUN_DIR/manifest.json" \
    "$RUN_DIR/2B/report.txt" \
    "$RUN_DIR/4B/report.txt" \
    "$RUN_DIR/device-and-runner.txt" \
    "$RUN_STAMP" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

manifest_path = Path(sys.argv[1])
report_2b = Path(sys.argv[2])
report_4b = Path(sys.argv[3])
provenance_path = Path(sys.argv[4])
run_stamp = sys.argv[5]

def parse_report(path: Path):
    values = {}
    first = True
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if first and line.startswith("LOCAL_LLM_PHONE_TEST "):
            first = False
            for part in line.split()[1:]:
                if "=" in part:
                    key, value = part.split("=", 1)
                    values[key] = value
            continue
        first = False
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value
    return values

def sha256(path: Path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

payload = {
    "schemaVersion": 1,
    "lane": "qwen35-2b-4b-ud-q4-k-xl-host-owned-physical-acceptance",
    "capturedAtUtc": run_stamp,
    "modelSource": "existing Harnex private ModelStore; no Mac-side GGUF transfer",
    "validationOwner": "Harnex PhoneTestController.runFullValidation",
    "reports": {
        "2B": parse_report(report_2b),
        "4B": parse_report(report_4b),
    },
    "artifacts": {
        "2BReportSha256": sha256(report_2b),
        "4BReportSha256": sha256(report_4b),
        "deviceAndRunnerSha256": sha256(provenance_path),
    },
}
manifest_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Manifest: {manifest_path}")
print(f"Manifest SHA-256: {sha256(manifest_path)}")
PY

echo
echo "Physical acceptance PASS for both exact UD-Q4_K_XL artifacts."
echo "Evidence: $RUN_DIR"
