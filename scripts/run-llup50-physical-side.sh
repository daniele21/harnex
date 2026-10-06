#!/usr/bin/env bash
set -euo pipefail

APP_ID="io.github.daniele21.localllm.devicetest.debug"
TEST_PACKAGE_ID="${APP_ID}.test"
ADB_BIN="${ADB:-adb}"
EXPECTED_08B_SHA="bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
EXPECTED_2B_SHA="aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
LABEL=""
APP_APK=""
TEST_APK=""
PACKAGE_MANIFEST=""
EXPECTED_EVIDENCE_SOURCE=""
RUNTIME_SOURCE=""
BACKEND_REVISION=""
MODEL_08B=""
MODEL_2B=""
DEVICE=""
OUTPUT_DIR="build/llup50-physical"
THERMAL_START_MAX=1
TIMEOUT_SECONDS=900
WARM_REPETITIONS=3
LOAD_REPETITIONS=3
MEMORY_REPEAT_COUNT=3
MAX_PSS_GROWTH_KB=131072

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/run-llup50-physical-side.sh \
    --label control|candidate \
    --app-apk /path/device-test-runner-debug.apk \
    --test-apk /path/device-test-runner-debug-androidTest.apk \
    --package-manifest /path/build-manifest.json \
    --expected-evidence-source <40-char SHA> \
    --runtime-source <40-char SHA> \
    --backend-revision <40-char SHA> \
    --model-08b /path/Qwen3.5-0.8B-Q4_K_M.gguf \
    --model-2b /path/Qwen3.5-2B-Q4_K_M.gguf \
    [--device SERIAL] [options]

Runs one serialized LLUP-50 physical side from CI-built app/test APKs. It never invokes
Gradle. The package manifest and APK SHA-256 identities are verified before install.
Run control and candidate on the same device with the same options, then compare their
manifests/logs; this script does not promote or apply ad-hoc performance thresholds.

Options:
  --output-dir PATH          Evidence root (default: build/llup50-physical).
  --thermal-start-max N      Maximum thermal status before each suite, 0..6 (default: 1).
  --timeout-seconds N        Instrumentation timeout (default: 900).
  --warm-repetitions N       Warm Qwen tuning samples, >=3 (default: 3).
  --load-repetitions N       Native model load samples, >=3 (default: 3).
  --memory-repeat N          Repeated lifecycle samples, >=3 (default: 3).
  --max-pss-growth-kb N      Existing repeated-cycle PSS budget (default: 131072).
  --help                     Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --label) LABEL="${2:-}"; shift 2 ;;
    --app-apk) APP_APK="${2:-}"; shift 2 ;;
    --test-apk) TEST_APK="${2:-}"; shift 2 ;;
    --package-manifest) PACKAGE_MANIFEST="${2:-}"; shift 2 ;;
    --expected-evidence-source) EXPECTED_EVIDENCE_SOURCE="${2:-}"; shift 2 ;;
    --runtime-source) RUNTIME_SOURCE="${2:-}"; shift 2 ;;
    --backend-revision) BACKEND_REVISION="${2:-}"; shift 2 ;;
    --model-08b) MODEL_08B="${2:-}"; shift 2 ;;
    --model-2b) MODEL_2B="${2:-}"; shift 2 ;;
    --device) DEVICE="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --thermal-start-max) THERMAL_START_MAX="${2:-}"; shift 2 ;;
    --timeout-seconds) TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
    --warm-repetitions) WARM_REPETITIONS="${2:-}"; shift 2 ;;
    --load-repetitions) LOAD_REPETITIONS="${2:-}"; shift 2 ;;
    --memory-repeat) MEMORY_REPEAT_COUNT="${2:-}"; shift 2 ;;
    --max-pss-growth-kb) MAX_PSS_GROWTH_KB="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$LABEL" == "control" || "$LABEL" == "candidate" ]] || { echo "--label must be control or candidate" >&2; exit 2; }
for value in "$EXPECTED_EVIDENCE_SOURCE" "$RUNTIME_SOURCE" "$BACKEND_REVISION"; do
  [[ "$value" =~ ^[0-9a-f]{40}$ ]] || { echo "Source/backend identities must be lowercase 40-character SHAs" >&2; exit 2; }
done
for file in "$APP_APK" "$TEST_APK" "$PACKAGE_MANIFEST" "$MODEL_08B" "$MODEL_2B"; do
  [[ -n "$file" && -f "$file" && -r "$file" ]] || { echo "Required file is missing or unreadable: $file" >&2; exit 2; }
done
for pair in \
  "thermal:$THERMAL_START_MAX" \
  "timeout:$TIMEOUT_SECONDS" \
  "warm:$WARM_REPETITIONS" \
  "load:$LOAD_REPETITIONS" \
  "memory:$MEMORY_REPEAT_COUNT" \
  "pss:$MAX_PSS_GROWTH_KB"; do
  name="${pair%%:*}"; value="${pair#*:}"
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "$name must be a non-negative integer" >&2; exit 2; }
done
(( THERMAL_START_MAX <= 6 )) || { echo "--thermal-start-max must be 0..6" >&2; exit 2; }
(( TIMEOUT_SECONDS > 0 && WARM_REPETITIONS >= 3 && LOAD_REPETITIONS >= 3 && MEMORY_REPEAT_COUNT >= 3 )) || {
  echo "timeout must be positive; warm/load/memory repetitions must be >=3" >&2; exit 2;
}
command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "adb is required" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then openssl dgst -sha256 "$1" | awk '{print $NF}'
  else echo "A SHA-256 utility is required" >&2; exit 2
  fi
}

ACTUAL_08B_SHA="$(sha256_file "$MODEL_08B" | tr '[:upper:]' '[:lower:]')"
ACTUAL_2B_SHA="$(sha256_file "$MODEL_2B" | tr '[:upper:]' '[:lower:]')"
[[ "$ACTUAL_08B_SHA" == "$EXPECTED_08B_SHA" ]] || { echo "0.8B model does not match curated Q4_K_M identity" >&2; exit 2; }
[[ "$ACTUAL_2B_SHA" == "$EXPECTED_2B_SHA" ]] || { echo "2B model does not match curated Q4_K_M identity" >&2; exit 2; }

PACKAGE_IDENTITY_JSON="$(python3 - "$PACKAGE_MANIFEST" "$APP_APK" "$TEST_APK" "$EXPECTED_EVIDENCE_SOURCE" <<'PY'
import hashlib, json, sys
from pathlib import Path
manifest_path, app_path, test_path = map(Path, sys.argv[1:4])
expected_source = sys.argv[4]
payload = json.loads(manifest_path.read_text(encoding="utf-8"))
artifacts = payload.get("artifacts", [])

def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def resolve(path, variant):
    matches = [a for a in artifacts if a.get("product") == "device-test-runner" and a.get("variant") == variant]
    if len(matches) != 1:
        raise SystemExit(f"package manifest must contain exactly one device-test-runner {variant} artifact")
    item = matches[0]
    if item.get("sourceRevision") != expected_source:
        raise SystemExit(f"package source mismatch for {variant}: {item.get('sourceRevision')} != {expected_source}")
    actual = digest(path)
    if actual != item.get("sha256"):
        raise SystemExit(f"APK digest mismatch for {variant}: {actual} != {item.get('sha256')}")
    return {"fileName": path.name, "sha256": actual, "buildId": item.get("buildId"), "sourceRevision": item.get("sourceRevision")}

print(json.dumps({"app": resolve(app_path, "debug"), "test": resolve(test_path, "androidTest")}, sort_keys=True))
PY
)"

ADB_CMD=("$ADB_BIN")
[[ -z "$DEVICE" ]] || ADB_CMD+=("-s" "$DEVICE")
"${ADB_CMD[@]}" get-state >/dev/null
DEVICE_MODEL="$("${ADB_CMD[@]}" shell getprop ro.product.model | tr -d '\r')"
DEVICE_RELEASE="$("${ADB_CMD[@]}" shell getprop ro.build.version.release | tr -d '\r')"
DEVICE_SDK="$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
DEVICE_ABI="$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$DEVICE_ABI" == arm64-v8a* ]] || { echo "LLUP-50 requires arm64-v8a; device reports $DEVICE_ABI" >&2; exit 2; }

RUN_DIR="$OUTPUT_DIR/$DEVICE_MODEL/$LABEL/$EXPECTED_EVIDENCE_SOURCE"
mkdir -p "$RUN_DIR"
printf '%s\n' "$PACKAGE_IDENTITY_JSON" > "$RUN_DIR/package-identity.json"

cleanup() {
  "${ADB_CMD[@]}" shell run-as "$APP_ID" rm -f files/e2e/qwen35-08b.gguf files/e2e/qwen35-2b.gguf >/dev/null 2>&1 || true
}
trap cleanup EXIT

"${ADB_CMD[@]}" uninstall "$TEST_PACKAGE_ID" >/dev/null 2>&1 || true
"${ADB_CMD[@]}" uninstall "$APP_ID" >/dev/null 2>&1 || true
"${ADB_CMD[@]}" install -t "$APP_APK"
"${ADB_CMD[@]}" install -t "$TEST_APK"
"${ADB_CMD[@]}" shell run-as "$APP_ID" mkdir -p files/e2e
"${ADB_CMD[@]}" shell -T run-as "$APP_ID" dd of=files/e2e/qwen35-08b.gguf bs=1048576 < "$MODEL_08B" >/dev/null
"${ADB_CMD[@]}" shell -T run-as "$APP_ID" dd of=files/e2e/qwen35-2b.gguf bs=1048576 < "$MODEL_2B" >/dev/null

RUNNER="$("${ADB_CMD[@]}" shell pm list instrumentation | tr -d '\r' | grep -F "(target=$APP_ID)" | head -n 1 | sed -E 's/^instrumentation:([^ ]+).*/\1/' || true)"
[[ -n "$RUNNER" ]] || { echo "Unable to discover AndroidJUnitRunner" >&2; exit 1; }

run_instrumentation() {
  local label="$1"; shift
  local log="$RUN_DIR/$label.log"
  set +e
  output="$("${ADB_CMD[@]}" shell am instrument -w -r "$@" "$RUNNER" 2>&1)"
  status=$?
  set -e
  output="${output//$'\r'/}"
  printf '%s\n' "$output" | tee "$log"
  if (( status != 0 )) || printf '%s\n' "$output" | grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg='; then
    echo "$label failed" >&2; exit 1
  fi
  printf '%s\n' "$output" | grep -Eq '^OK \(' || { echo "$label missing JUnit success marker" >&2; exit 1; }
  printf '%s\n' "$output" | grep -Eq '^INSTRUMENTATION_CODE: -1$' || { echo "$label missing instrumentation success marker" >&2; exit 1; }
}

read_thermal_status() {
  set +e
  output="$("${ADB_CMD[@]}" shell am instrument -w -r -e class io.github.daniele21.localllm.devicetest.Qwen35TuningInstrumentedTest#reportsThermalStatus "$RUNNER" 2>&1)"
  status=$?
  set -e
  output="${output//$'\r'/}"
  (( status == 0 )) || { printf '%s\n' "$output" >&2; return 1; }
  value="$(printf '%s\n' "$output" | sed -n 's/^.*LOCAL_LLM_THERMAL_STATUS //p' | tail -n 1)"
  [[ "$value" =~ ^-?[0-9]+$ ]] || { echo "Unable to parse thermal status" >&2; return 1; }
  printf '%s\n' "$value"
}

wait_for_thermal_gate() {
  while true; do
    thermal="$(read_thermal_status)"
    if (( thermal < 0 || thermal <= THERMAL_START_MAX )); then
      echo "Thermal gate satisfied: status=$thermal"
      return 0
    fi
    echo "Thermal status=$thermal; cooling before next serialized suite"
    sleep 30
  done
}

run_load_latency() {
  local tier="$1" path="$2" sha="$3" threads="$4"
  wait_for_thermal_gate
  run_instrumentation "${tier}-load" \
    -e class io.github.daniele21.localllm.devicetest.LlupModelLoadLatencyInstrumentedTest#recordsModelLoadLatencyEvidence \
    -e modelRelativePath "$path" \
    -e modelSha256 "$sha" \
    -e modelTier "$tier" \
    -e cpuThreads "$threads" \
    -e loadRepetitions "$LOAD_REPETITIONS" \
    -e runtimeSourceCommit "$RUNTIME_SOURCE" \
    -e evidenceHarnessCommit "$EXPECTED_EVIDENCE_SOURCE" \
    -e backendRevision "$BACKEND_REVISION"
  count="$(sed -n 's/^.*LOCAL_LLM_LLUP_LOAD_JSON //p' "$RUN_DIR/${tier}-load.log" | wc -l | tr -d ' ')"
  [[ "$count" -eq "$LOAD_REPETITIONS" ]] || { echo "$tier load evidence count mismatch" >&2; exit 1; }
}

run_tuning() {
  local tier="$1" path="$2" sha="$3" threads="$4"
  wait_for_thermal_gate
  run_instrumentation "${tier}-tuning" \
    -e class io.github.daniele21.localllm.devicetest.Qwen35TuningInstrumentedTest#recordsColdAndWarmEvidence \
    -e modelRelativePath "$path" \
    -e modelSha256 "$sha" \
    -e modelTier "$tier" \
    -e contextSize 2048 \
    -e batchSize 128 \
    -e microBatchSize 64 \
    -e cpuThreads "$threads" \
    -e batchThreads "$threads" \
    -e maxOutputTokens 64 \
    -e warmRepetitions "$WARM_REPETITIONS" \
    -e thinkingMode DISABLED \
    -e generationSeed 42 \
    -e evidenceSchemaVersion 5 \
    -e tuningCaseId "llup50-${LABEL}-${tier}" \
    -e harnessCommit "$RUNTIME_SOURCE" \
    -e timeoutSeconds "$TIMEOUT_SECONDS"
  count="$(sed -n 's/^.*LOCAL_LLM_TUNING_JSON //p' "$RUN_DIR/${tier}-tuning.log" | wc -l | tr -d ' ')"
  expected=$((WARM_REPETITIONS + 1))
  [[ "$count" -eq "$expected" ]] || { echo "$tier tuning evidence count mismatch" >&2; exit 1; }
}

run_e2e() {
  local tier="$1" path="$2" sha="$3" threads="$4"
  wait_for_thermal_gate
  run_instrumentation "${tier}-e2e" \
    -e class io.github.daniele21.localllm.devicetest.LocalLlmDeviceE2eTest \
    -e modelRelativePath "$path" \
    -e modelSha256 "$sha" \
    -e modelArchitecture qwen35 \
    -e modelQuantization Q4_K_M \
    -e contextSize 2048 \
    -e batchSize 128 \
    -e microBatchSize 64 \
    -e cpuThreads "$threads" \
    -e cancellationEnabled true \
    -e memoryRepeatCount "$MEMORY_REPEAT_COUNT" \
    -e maxPssGrowthKb "$MAX_PSS_GROWTH_KB" \
    -e timeoutSeconds "$TIMEOUT_SECONDS"
  grep -Fq 'LOCAL_LLM_E2E cancellation terminal=cancelled' "$RUN_DIR/${tier}-e2e.log" || { echo "$tier missing cancellation evidence" >&2; exit 1; }
  grep -Fq 'LOCAL_LLM_E2E memory pssSamplesKb=' "$RUN_DIR/${tier}-e2e.log" || { echo "$tier missing repeated-memory evidence" >&2; exit 1; }
}

run_lifecycle() {
  local label="$1" method="$2" ppath="$3" psha="$4" pthreads="$5" spath="$6" ssha="$7" sthreads="$8"
  wait_for_thermal_gate
  run_instrumentation "$label" \
    -e class "io.github.daniele21.localllm.devicetest.Qwen35LifecycleAcceptanceInstrumentedTest#$method" \
    -e primaryModelRelativePath "$ppath" \
    -e primaryModelSha256 "$psha" \
    -e primaryCpuThreads "$pthreads" \
    -e secondaryModelRelativePath "$spath" \
    -e secondaryModelSha256 "$ssha" \
    -e secondaryCpuThreads "$sthreads" \
    -e contextTokens 2048 \
    -e batchSize 128 \
    -e microBatchSize 64 \
    -e batchThreads 4 \
    -e switchOutputTokens 8 \
    -e lowMemoryOutputTokens 256 \
    -e timeoutSeconds "$TIMEOUT_SECONDS" \
    -e harnessCommit "$RUNTIME_SOURCE" \
    -e backendRevision "$BACKEND_REVISION"
  grep -Fq 'LOCAL_LLM_Q35_LIFECYCLE_JSON ' "$RUN_DIR/$label.log" || { echo "$label missing structured lifecycle evidence" >&2; exit 1; }
}

run_load_latency 0.8b files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2
run_load_latency 2b files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4
run_tuning 0.8b files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2
run_tuning 2b files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4
run_e2e 0.8b files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2
run_e2e 2b files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4
run_lifecycle 08b-low-memory lowMemoryDuringActiveGenerationCancelsAndReleasesEverything \
  files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2 files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4
run_lifecycle 2b-low-memory lowMemoryDuringActiveGenerationCancelsAndReleasesEverything \
  files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4 files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2
run_lifecycle cross-tier-switch switchesBetweenReferenceModelsWithoutResidencyLeak \
  files/e2e/qwen35-08b.gguf "$ACTUAL_08B_SHA" 2 files/e2e/qwen35-2b.gguf "$ACTUAL_2B_SHA" 4

LOAD_JSONL="$RUN_DIR/model-load-evidence.jsonl"
TUNING_JSONL="$RUN_DIR/tuning-evidence.jsonl"
LIFECYCLE_JSONL="$RUN_DIR/lifecycle-evidence.jsonl"
: > "$LOAD_JSONL"; : > "$TUNING_JSONL"; : > "$LIFECYCLE_JSONL"
for log in "$RUN_DIR"/*-load.log; do sed -n 's/^.*LOCAL_LLM_LLUP_LOAD_JSON //p' "$log" >> "$LOAD_JSONL"; done
for log in "$RUN_DIR"/*-tuning.log; do sed -n 's/^.*LOCAL_LLM_TUNING_JSON //p' "$log" >> "$TUNING_JSONL"; done
for log in "$RUN_DIR"/*-low-memory.log "$RUN_DIR/cross-tier-switch.log"; do sed -n 's/^.*LOCAL_LLM_Q35_LIFECYCLE_JSON //p' "$log" >> "$LIFECYCLE_JSONL"; done

MANIFEST="$RUN_DIR/manifest.json"
python3 - "$MANIFEST" "$LABEL" "$EXPECTED_EVIDENCE_SOURCE" "$RUNTIME_SOURCE" "$BACKEND_REVISION" \
  "$DEVICE_MODEL" "$DEVICE_RELEASE" "$DEVICE_SDK" "$DEVICE_ABI" "$ACTUAL_08B_SHA" "$ACTUAL_2B_SHA" \
  "$THERMAL_START_MAX" "$WARM_REPETITIONS" "$LOAD_REPETITIONS" "$MEMORY_REPEAT_COUNT" "$MAX_PSS_GROWTH_KB" \
  "$TIMEOUT_SECONDS" "$RUN_DIR" <<'PY'
import hashlib, json, sys
from pathlib import Path
manifest = Path(sys.argv[1]); run_dir = Path(sys.argv[18])

def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def logs(pattern):
    return {p.name: digest(p) for p in sorted(run_dir.glob(pattern))}

payload = {
    "schemaVersion": 1,
    "evidenceType": "LLUP50_PHYSICAL_SIDE",
    "label": sys.argv[2],
    "evidenceSourceCommit": sys.argv[3],
    "runtimeSourceCommit": sys.argv[4],
    "backendRevision": sys.argv[5],
    "deviceModel": sys.argv[6],
    "androidRelease": sys.argv[7],
    "sdkInt": int(sys.argv[8]),
    "abi": sys.argv[9],
    "model08bDigest": sys.argv[10],
    "model2bDigest": sys.argv[11],
    "thermalStartMax": int(sys.argv[12]),
    "warmRepetitions": int(sys.argv[13]),
    "loadRepetitions": int(sys.argv[14]),
    "memoryRepeatCount": int(sys.argv[15]),
    "maxPssGrowthKb": int(sys.argv[16]),
    "timeoutSeconds": int(sys.argv[17]),
    "profile": {"contextTokens": 2048, "batchSize": 128, "microBatchSize": 64, "thinkingMode": "DISABLED", "generationSeed": 42},
    "evidenceFiles": {
        "packageIdentity": digest(run_dir / "package-identity.json"),
        "modelLoad": digest(run_dir / "model-load-evidence.jsonl"),
        "tuning": digest(run_dir / "tuning-evidence.jsonl"),
        "lifecycle": digest(run_dir / "lifecycle-evidence.jsonl"),
    },
    "logs": logs("*.log"),
    "automaticPromotion": False,
}
manifest.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"LLUP-50 side manifest: {manifest}")
print(f"Manifest SHA-256: {digest(manifest)}")
PY

echo "LLUP-50 $LABEL physical side completed; no promotion decision was applied."
