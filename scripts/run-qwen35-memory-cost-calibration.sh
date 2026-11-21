#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

APP_ID="io.github.daniele21.localllm.devicetest.debug"
BACKEND_REVISION="aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3"
EXPECTED_08B_SHA="bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
EXPECTED_2B_SHA="aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
MODEL_08B=""
MODEL_2B=""
DEVICE=""
OUTPUT_DIR="build/q35-memory-cost-calibration"
CONTEXTS="1024,2048"
REPEATS=3
THERMAL_START_MAX=1
ADB_BIN="${ADB:-adb}"

usage() {
    cat <<'EOF'
Usage: bash scripts/run-qwen35-memory-cost-calibration.sh \
  --model-08b /path/0.8b.gguf --model-2b /path/2b.gguf [options]

Records native model-load/context-create/generation memory windows, one cold
instrumentation process per exact tier/context/repetition. Output is UNREVIEWED
CANDIDATE evidence, not a MEASURED profile or an admission authorization.

  --device SERIAL            ADB physical arm64 device (default: single connected device)
  --contexts 1024,2048       Approved tiers to measure (default: 1024,2048)
  --repeats 3                Cold repetitions per tier/context (minimum: 3)
  --thermal-start-max 1      Maximum Android thermal status before each case (0..6)
  --output-dir PATH          Must not already exist; never overwrites physical evidence
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --model-08b) MODEL_08B="$2"; shift 2 ;;
        --model-2b) MODEL_2B="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --contexts) CONTEXTS="$2"; shift 2 ;;
        --repeats) REPEATS="$2"; shift 2 ;;
        --thermal-start-max) THERMAL_START_MAX="$2"; shift 2 ;;
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

for path in "$MODEL_08B" "$MODEL_2B"; do
    [[ -f "$path" && -r "$path" ]] || { echo "Both curated GGUFs must be readable" >&2; exit 2; }
done
[[ "$REPEATS" =~ ^[0-9]+$ ]] && (( REPEATS >= 3 )) ||
    { echo "--repeats must be >= 3" >&2; exit 2; }
[[ "$THERMAL_START_MAX" =~ ^[0-9]+$ ]] && (( THERMAL_START_MAX <= 6 )) ||
    { echo "--thermal-start-max must be 0..6" >&2; exit 2; }

IFS=',' read -r -a CONTEXT_TIERS <<< "$CONTEXTS"
(( ${#CONTEXT_TIERS[@]} > 0 )) || { echo "Missing context tiers" >&2; exit 2; }
SEEN_CONTEXTS=","
for tier in "${CONTEXT_TIERS[@]}"; do
    case "$tier" in 1024|2048|4096|8192) ;; *)
        echo "Unsupported Qwen3.5 context tier: $tier" >&2; exit 2 ;;
    esac
    case "$SEEN_CONTEXTS" in
        *,"$tier",*) echo "Duplicate context tier" >&2; exit 2 ;;
    esac
    SEEN_CONTEXTS="$SEEN_CONTEXTS$tier,"
done

command -v "$ADB_BIN" >/dev/null || { echo "adb is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
command -v git >/dev/null || { echo "git is required" >&2; exit 2; }

require_clean_identity() {
    stage="$1"
    [[ -z "$(git status --short --untracked-files=no --ignore-submodules=dirty)" ]] ||
        { echo "Tracked Harness worktree is dirty $stage" >&2; exit 1; }
    [[ -e third_party/llama.cpp/.git ]] ||
        { echo "llama.cpp submodule is not initialized $stage" >&2; exit 1; }
    [[ -z "$(git -C third_party/llama.cpp status --short)" ]] ||
        { echo "llama.cpp is dirty $stage" >&2; exit 1; }
    [[ "$(git -C third_party/llama.cpp rev-parse HEAD)" == "$BACKEND_REVISION" ]] ||
        { echo "llama.cpp backend pin mismatch $stage" >&2; exit 1; }
}
sha256_file() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}
require_clean_identity "before calibration"
HARNESS_COMMIT="$(git rev-parse HEAD)"
[[ "$(sha256_file "$MODEL_08B")" == "$EXPECTED_08B_SHA" ]] ||
    { echo "0.8B artifact digest mismatch" >&2; exit 2; }
[[ "$(sha256_file "$MODEL_2B")" == "$EXPECTED_2B_SHA" ]] ||
    { echo "2B artifact digest mismatch" >&2; exit 2; }

ADB_CMD=("$ADB_BIN")
if [[ -n "$DEVICE" ]]; then ADB_CMD+=("-s" "$DEVICE"); fi
"${ADB_CMD[@]}" get-state >/dev/null
DEVICE_MODEL="$("${ADB_CMD[@]}" shell getprop ro.product.model | tr -d '\r')"
DEVICE_RELEASE="$("${ADB_CMD[@]}" shell getprop ro.build.version.release | tr -d '\r')"
DEVICE_SDK="$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_ABI="$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$DEVICE_ABI" == arm64-v8a* ]] || { echo "Physical ARM64 is required" >&2; exit 2; }
[[ -n "$DEVICE_MODEL" && "$DEVICE_SDK" =~ ^[0-9]+$ ]] ||
    { echo "Incomplete device identity" >&2; exit 2; }

RUN_DIR="$OUTPUT_DIR/$DEVICE_MODEL/$HARNESS_COMMIT"
[[ ! -e "$RUN_DIR" ]] || { echo "Evidence directory already exists; select another --output-dir" >&2; exit 2; }
mkdir -p "$RUN_DIR"
cleanup() {
    "${ADB_CMD[@]}" shell run-as "$APP_ID" rm -f \
        files/e2e/qwen35-08b.gguf files/e2e/qwen35-2b.gguf >/dev/null 2>&1 || true
    require_clean_identity "after calibration"
}
trap cleanup EXIT

./gradlew :apps:device-test-runner:assembleDebug :apps:device-test-runner:assembleDebugAndroidTest
APP_APK="$(find apps/device-test-runner/build/outputs/apk/debug -type f -name '*.apk' | sort | tail -n 1)"
TEST_APK="$(find apps/device-test-runner/build/outputs/apk/androidTest/debug -type f -name '*.apk' | sort | tail -n 1)"
[[ -f "$APP_APK" && -f "$TEST_APK" ]] || { echo "Missing device-test APKs" >&2; exit 1; }
"${ADB_CMD[@]}" install -r -t "$APP_APK"
"${ADB_CMD[@]}" install -r -t "$TEST_APK"
"${ADB_CMD[@]}" shell run-as "$APP_ID" mkdir -p files/e2e
"${ADB_CMD[@]}" shell -T run-as "$APP_ID" dd of=files/e2e/qwen35-08b.gguf bs=1048576 < "$MODEL_08B" >/dev/null
"${ADB_CMD[@]}" shell -T run-as "$APP_ID" dd of=files/e2e/qwen35-2b.gguf bs=1048576 < "$MODEL_2B" >/dev/null

RUNNER="$("${ADB_CMD[@]}" shell pm list instrumentation | tr -d '\r' |
    grep -F "(target=$APP_ID)" | head -n 1 | sed -E 's/^instrumentation:([^ ]+).*/\1/' || true)"
[[ -n "$RUNNER" ]] || { echo "Instrumentation runner unavailable" >&2; exit 1; }

read_thermal_status() {
    output="$("${ADB_CMD[@]}" shell am instrument -w -r \
        -e class io.github.daniele21.localllm.devicetest.Qwen35TuningInstrumentedTest#reportsThermalStatus \
        "$RUNNER")"
    printf '%s\n' "$output" | grep -Eq '^OK \(' ||
        { echo "Thermal instrumentation did not pass" >&2; return 1; }
    printf '%s\n' "$output" | grep -Eq '^INSTRUMENTATION_CODE: -1$' ||
        { echo "Thermal instrumentation failed" >&2; return 1; }
    status="$(printf '%s\n' "$output" | tr -d '\r' |
        sed -n 's/^.*LOCAL_LLM_THERMAL_STATUS //p' | tail -n 1)"
    [[ "$status" =~ ^[0-9]+$ ]] || { echo "No thermal evidence" >&2; return 1; }
    printf '%s' "$status"
}
wait_for_thermal_gate() {
    while true; do
        thermal="$(read_thermal_status)"
        if (( thermal <= THERMAL_START_MAX )); then
            echo "Thermal start gate satisfied: status=$thermal limit=$THERMAL_START_MAX"
            return 0
        fi
        echo "Thermal status=$thermal; cooling before next serialized case"
        sleep 30
    done
}

RAW="$RUN_DIR/raw-memory-cost.jsonl"
: > "$RAW"
for reference in 08b 2b; do
    if [[ "$reference" == 08b ]]; then
        model_digest="$EXPECTED_08B_SHA"
        model_path="files/e2e/qwen35-08b.gguf"
        profile="qwen35-08b-android-candidate"
        threads=2
        tier="0.8b"
    else
        model_digest="$EXPECTED_2B_SHA"
        model_path="files/e2e/qwen35-2b.gguf"
        profile="qwen35-2b-android-candidate"
        threads=4
        tier="2b"
    fi
    for context in "${CONTEXT_TIERS[@]}"; do
        for ((iteration=1; iteration<=REPEATS; iteration++)); do
            wait_for_thermal_gate
            label="$reference-ctx$context-r$iteration"
            log="$RUN_DIR/$label.log"
            echo "Calibrating $label on $DEVICE_MODEL, Harness $HARNESS_COMMIT"
            set +e
            output="$("${ADB_CMD[@]}" shell am instrument -w -r \
                -e class io.github.daniele21.localllm.devicetest.Qwen35MemoryCostCalibrationInstrumentedTest \
                -e tier "$tier" \
                -e modelRelativePath "$model_path" \
                -e modelSha256 "$model_digest" \
                -e runtimeProfileId "$profile" \
                -e runtimeProfileVersion 1 \
                -e contextTokens "$context" \
                -e cpuThreads "$threads" \
                -e batchThreads 4 \
                -e outputTokens 16 \
                -e harnessCommit "$HARNESS_COMMIT" \
                -e backendRevision "$BACKEND_REVISION" \
                "$RUNNER" 2>&1)"
            status=$?
            set -e
            printf '%s\n' "$output" | tr -d '\r' > "$log"
            (( status == 0 )) || { echo "Instrumentation failed: $label" >&2; exit 1; }
            grep -Eq '^OK \(' "$log" || { echo "Missing JUnit PASS: $label" >&2; exit 1; }
            grep -Eq '^INSTRUMENTATION_CODE: -1$' "$log" ||
                { echo "Missing instrumentation terminal PASS: $label" >&2; exit 1; }
            evidence="$(sed -n 's/^.*LOCAL_LLM_MEMORY_CALIBRATION_JSON //p' "$log")"
            [[ "$(printf '%s\n' "$evidence" | grep -c .)" -eq 1 ]] ||
                { echo "Expected one structured calibration sample: $label" >&2; exit 1; }
            printf '%s\n' "$evidence" >> "$RAW"
        done
    done
done

python3 scripts/summarize-qwen35-memory-calibration.py \
    "$RAW" "$RUN_DIR/candidate-summary.json" \
    --harness-commit "$HARNESS_COMMIT" \
    --backend-revision "$BACKEND_REVISION" \
    --repeats "$REPEATS" --contexts "$CONTEXTS"

python3 - "$RUN_DIR" "$HARNESS_COMMIT" "$BACKEND_REVISION" "$DEVICE_MODEL" \
    "$DEVICE_RELEASE" "$DEVICE_SDK" "$DEVICE_ABI" "$EXPECTED_08B_SHA" "$EXPECTED_2B_SHA" \
    "$CONTEXTS" "$REPEATS" "$THERMAL_START_MAX" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
logs = sorted(root.glob("*.log"))
payload = {
    "schemaVersion": 1,
    "evidenceType": "Q35_NATIVE_MEMORY_COST_CANDIDATE",
    "status": "CANDIDATE_UNREVIEWED",
    "automaticProfilePromotion": False,
    "harnessCommit": sys.argv[2],
    "backendRevision": sys.argv[3],
    "deviceModel": sys.argv[4],
    "androidRelease": sys.argv[5],
    "sdkInt": int(sys.argv[6]),
    "abi": sys.argv[7],
    "model08bDigest": sys.argv[8],
    "model2bDigest": sys.argv[9],
    "contextTiers": [int(value) for value in sys.argv[10].split(",")],
    "coldRepetitions": int(sys.argv[11]),
    "thermalStartMax": int(sys.argv[12]),
    "rawEvidenceSha256": digest(root / "raw-memory-cost.jsonl"),
    "candidateSummarySha256": digest(root / "candidate-summary.json"),
    "caseLogDigests": {log.name: digest(log) for log in logs},
}
manifest = root / "manifest.json"
manifest.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
print("Candidate manifest SHA-256:", digest(manifest))
PY

require_clean_identity "after evidence capture"
echo "Physical cost evidence captured as CANDIDATE_UNREVIEWED only; no MEASURED profile promoted."
