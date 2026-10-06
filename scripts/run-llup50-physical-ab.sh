#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB_BIN="${ADB:-adb}"
REPO="daniele21/android-local-llm-harness"

# Frozen LLUP-50 qualification identities. Keep these aligned with the package evidence lane.
DEFAULT_ARTIFACT_RUN_ID="33334957429"
CONTROL_EVIDENCE_SHA="fcbefc7cd9af84de570da96d039582175dd1700b"
CONTROL_RUNTIME_SHA="80164329bbc41a00b75721e3d0524294c03fdb56"
CONTROL_BACKEND_REVISION="aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3"
CANDIDATE_EVIDENCE_SHA="a2a050d9551db541bb4c6b152cba8623c782164d"
CANDIDATE_RUNTIME_SHA="59af48313b450d9cff13c7f43458c2e5e6560374"
CANDIDATE_BACKEND_REVISION="c1d0e7a004015f23bc0233470b747b596f29b264"
CONTROL_ARTIFACT="llup50-control-device-apks"
CANDIDATE_ARTIFACT="llup50-candidate-device-apks"
APP_ID="io.github.daniele21.localllm.devicetest.debug"
TEST_PACKAGE_ID="${APP_ID}.test"

QWEN35_08B_URL="https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/5aea8824cba95d22990acc6ea66c2c1909530650/Qwen3.5-0.8B-Q4_K_M.gguf?download=true"
QWEN35_2B_URL="https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/802854bfd388ed92748de119df31327962811548/Qwen3.5-2B-Q4_K_M.gguf?download=true"

MODEL_08B=""
MODEL_2B=""
DOWNLOAD_MODELS=false
MODEL_CACHE="${HOME}/.cache/android-local-llm-harness/llup50"
DEVICE=""
ARTIFACT_RUN_ID="$DEFAULT_ARTIFACT_RUN_ID"
ARTIFACT_ROOT=""
OUTPUT_ROOT="$ROOT_DIR/build/llup50-physical-ab"
THERMAL_START_MAX=1
TIMEOUT_SECONDS=900
WARM_REPETITIONS=3
LOAD_REPETITIONS=3
MEMORY_REPEAT_COUNT=3
MAX_PSS_GROWTH_KB=131072

usage() {
  cat <<'USAGE'
Usage:
  bash scripts/run-llup50-physical-ab.sh \
    --model-0.8b /path/Qwen3.5-0.8B-Q4_K_M.gguf \
    --model-2b /path/Qwen3.5-2B-Q4_K_M.gguf \
    [--device SERIAL] [options]

Or let the controller download the exact curated GGUFs once into a local cache:
  bash scripts/run-llup50-physical-ab.sh --download-models [--device SERIAL]

One-command LLUP-50 physical A/B controller. It:
  1. validates the connected physical ARM64 device;
  2. downloads the frozen CI-built control/candidate APK artifacts from GitHub Actions;
  3. verifies source revision and APK SHA-256 through the canonical side runner;
  4. runs control then candidate on the same device with the same profile and thermal gate;
  5. runs the canonical A/B comparator;
  6. writes a final PASS / FAIL / INCONCLUSIVE result and preserves bounded evidence.

PASS means the physical A/B executed successfully and produced comparable evidence.
It does NOT make the LLUP-70 promotion decision and does not invent performance thresholds.

Options:
  --model-0.8b PATH          Exact curated Qwen3.5 0.8B Q4_K_M GGUF.
  --model-2b PATH            Exact curated Qwen3.5 2B Q4_K_M GGUF.
  --download-models          Download both exact GGUFs when local paths are omitted.
  --model-cache PATH         Model cache used with --download-models.
  --device SERIAL            Exact ADB serial; auto-resolved when one physical device is online.
  --artifact-run-id ID       GitHub Actions package run (default: frozen LLUP-50 run 33334957429).
  --artifact-root PATH       Use already-downloaded artifacts; skips gh download.
  --output-dir PATH          Evidence root (default: build/llup50-physical-ab).
  --thermal-start-max N      Maximum thermal status before suites, 0..6 (default: 1).
  --timeout-seconds N        Instrumentation timeout (default: 900).
  --warm-repetitions N       Warm tuning samples, >=3 (default: 3).
  --load-repetitions N       Model-load samples, >=3 (default: 3).
  --memory-repeat N          Repeated lifecycle samples, >=3 (default: 3).
  --max-pss-growth-kb N      Existing repeated-cycle PSS budget (default: 131072).
  --help                     Show this help.

Exit codes:
  0  PASS          physical A/B completed and evidence is comparable.
  1  FAIL          candidate physical instrumentation failed after a valid control.
  2  INCONCLUSIVE  environment, control baseline, artifact identity, or comparison was invalid.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --model-0.8b) MODEL_08B="${2:-}"; shift 2 ;;
    --model-2b) MODEL_2B="${2:-}"; shift 2 ;;
    --download-models) DOWNLOAD_MODELS=true; shift ;;
    --model-cache) MODEL_CACHE="${2:-}"; shift 2 ;;
    --device) DEVICE="${2:-}"; shift 2 ;;
    --artifact-run-id) ARTIFACT_RUN_ID="${2:-}"; shift 2 ;;
    --artifact-root) ARTIFACT_ROOT="${2:-}"; shift 2 ;;
    --output-dir) OUTPUT_ROOT="${2:-}"; shift 2 ;;
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

for pair in \
  "artifact-run:$ARTIFACT_RUN_ID" \
  "thermal:$THERMAL_START_MAX" \
  "timeout:$TIMEOUT_SECONDS" \
  "warm:$WARM_REPETITIONS" \
  "load:$LOAD_REPETITIONS" \
  "memory:$MEMORY_REPEAT_COUNT" \
  "pss:$MAX_PSS_GROWTH_KB"; do
  name="${pair%%:*}"; value="${pair#*:}"
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "$name must be a non-negative integer" >&2; exit 2; }
done
(( ARTIFACT_RUN_ID > 0 && THERMAL_START_MAX <= 6 && TIMEOUT_SECONDS > 0 )) || {
  echo "artifact run must be positive, thermal must be 0..6, and timeout must be positive" >&2
  exit 2
}
(( WARM_REPETITIONS >= 3 && LOAD_REPETITIONS >= 3 && MEMORY_REPEAT_COUNT >= 3 )) || {
  echo "warm/load/memory repetitions must be >=3" >&2
  exit 2
}

command -v "$ADB_BIN" >/dev/null 2>&1 || { echo "adb is required" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 2; }
command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 2; }
[[ -x "$ROOT_DIR/scripts/run-llup50-physical-side.sh" || -f "$ROOT_DIR/scripts/run-llup50-physical-side.sh" ]] || {
  echo "Missing canonical side runner: scripts/run-llup50-physical-side.sh" >&2; exit 2;
}
[[ -f "$ROOT_DIR/scripts/compare-llup50-evidence.py" ]] || {
  echo "Missing canonical comparator: scripts/compare-llup50-evidence.py" >&2; exit 2;
}
[[ -f "$ROOT_DIR/scripts/llrt-device-preflight.sh" ]] || {
  echo "Missing physical-device preflight: scripts/llrt-device-preflight.sh" >&2; exit 2;
}

resolve_device() {
  if [[ -n "$DEVICE" ]]; then
    "$ADB_BIN" -s "$DEVICE" get-state >/dev/null
    return
  fi
  online_devices=()
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && online_devices+=("$serial")
  done < <("$ADB_BIN" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if (( ${#online_devices[@]} != 1 )); then
    echo "Expected exactly one online ADB device; found ${#online_devices[@]}. Use --device SERIAL." >&2
    "$ADB_BIN" devices >&2
    exit 2
  fi
  DEVICE="${online_devices[0]}"
}
resolve_device
ADB_CMD=("$ADB_BIN" "-s" "$DEVICE")

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${DEVICE//[^A-Za-z0-9._-]/_}"
RUN_DIR="$OUTPUT_ROOT/$RUN_ID"
LOG_DIR="$RUN_DIR/logs"
SIDE_OUTPUT="$RUN_DIR/sides"
COMPARE_DIR="$RUN_DIR/comparison"
STAGE_TSV="$RUN_DIR/stages.tsv"
RESULT_JSON="$RUN_DIR/result.json"
RESULT_MD="$RUN_DIR/result.md"
mkdir -p "$LOG_DIR" "$SIDE_OUTPUT" "$COMPARE_DIR"
printf 'stage\tstatus\texitCode\tnote\n' > "$STAGE_TSV"

record_stage() {
  printf '%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$(printf '%s' "$4" | tr '\t\r\n' '   ')" >> "$STAGE_TSV"
}

cleanup_device() {
  "${ADB_CMD[@]}" shell run-as "$APP_ID" rm -f files/e2e/qwen35-08b.gguf files/e2e/qwen35-2b.gguf >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" uninstall "$TEST_PACKAGE_ID" >/dev/null 2>&1 || true
  "${ADB_CMD[@]}" uninstall "$APP_ID" >/dev/null 2>&1 || true
}
trap cleanup_device EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

write_result() {
  local verdict="$1" reason="$2" exit_code="$3"
  local tooling_commit="unknown"
  tooling_commit="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf unknown)"
  python3 - "$RESULT_JSON" "$RESULT_MD" "$STAGE_TSV" "$verdict" "$reason" "$DEVICE" "$ARTIFACT_RUN_ID" \
    "$tooling_commit" "$CONTROL_RUNTIME_SHA" "$CANDIDATE_RUNTIME_SHA" "$CONTROL_EVIDENCE_SHA" "$CANDIDATE_EVIDENCE_SHA" "$COMPARE_DIR" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

(
    json_path, md_path, stages_path, verdict, reason, device, artifact_run_id,
    tooling_commit, control_runtime, candidate_runtime, control_evidence,
    candidate_evidence, compare_dir,
) = sys.argv[1:]
stages = []
for line in Path(stages_path).read_text(encoding="utf-8").splitlines()[1:]:
    if not line.strip():
        continue
    name, status, code, note = line.split("\t", 3)
    stages.append({"stage": name, "status": status, "exitCode": int(code), "note": note})
comparison_json = Path(compare_dir) / "llup50-comparison.json"
payload = {
    "schemaVersion": 1,
    "evidenceType": "LLUP50_PHYSICAL_AB_CONTROLLER_RESULT",
    "finishedAtUtc": datetime.now(timezone.utc).isoformat(),
    "physicalTestResult": verdict,
    "reason": reason,
    "deviceSerial": device,
    "artifactRunId": int(artifact_run_id),
    "toolingCommit": tooling_commit,
    "control": {"runtimeSourceCommit": control_runtime, "evidenceSourceCommit": control_evidence},
    "candidate": {"runtimeSourceCommit": candidate_runtime, "evidenceSourceCommit": candidate_evidence},
    "stages": stages,
    "comparison": str(comparison_json) if comparison_json.is_file() else None,
    "promotionDecision": "UNSET",
    "performanceThresholdPolicyApplied": False,
    "semantics": "PASS proves successful comparable physical evidence capture; LLUP-70 remains a separate explicit promotion decision",
}
Path(json_path).write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
lines = [
    "# LLUP-50 physical A/B result",
    "",
    f"**Result: {verdict}**",
    "",
    f"Reason: {reason}",
    "",
    f"- device serial: `{device}`",
    f"- GitHub Actions artifact run: `{artifact_run_id}`",
    f"- tooling commit: `{tooling_commit}`",
    f"- control runtime: `{control_runtime}`",
    f"- candidate runtime: `{candidate_runtime}`",
    "- LLUP-70 promotion decision: **UNSET**",
    "- performance thresholds: **not applied**",
    "",
    "## Stages",
    "",
    "| Stage | Status | Exit | Note |",
    "| --- | --- | ---: | --- |",
]
for item in stages:
    lines.append(f"| {item['stage']} | {item['status']} | {item['exitCode']} | {item['note']} |")
if comparison_json.is_file():
    lines.extend(["", f"Detailed A/B metrics: `{comparison_json}` and `{comparison_json.with_suffix('.md')}`."])
Path(md_path).write_text("\n".join(lines) + "\n", encoding="utf-8")
PY
  echo
  echo "============================================================"
  echo "PHYSICAL_TEST_RESULT: $verdict"
  echo "$reason"
  echo "Evidence: $RUN_DIR"
  echo "Summary:  $RESULT_MD"
  echo "============================================================"
  return "$exit_code"
}

run_logged() {
  local log="$1"; shift
  "$@" 2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

echo "== LLUP-50 physical A/B controller =="
echo "device:       $DEVICE"
echo "artifact run: $ARTIFACT_RUN_ID"
echo "evidence:     $RUN_DIR"
echo

run_logged "$LOG_DIR/device-preflight.log" bash "$ROOT_DIR/scripts/llrt-device-preflight.sh" --device "$DEVICE"
rc=$?
if (( rc != 0 )); then
  record_stage "device-preflight" "INCONCLUSIVE" "$rc" "physical-device preflight failed"
  write_result "INCONCLUSIVE" "Device preflight did not establish a valid representative physical environment." 2
  exit $?
fi
record_stage "device-preflight" "PASS" 0 "physical ARM64 device accepted"

if [[ -z "$MODEL_08B" || -z "$MODEL_2B" ]]; then
  if [[ "$DOWNLOAD_MODELS" != true ]]; then
    record_stage "models" "INCONCLUSIVE" 2 "model paths missing and --download-models not requested"
    write_result "INCONCLUSIVE" "Both curated GGUF model paths are required, or use --download-models." 2
    exit $?
  fi
  command -v curl >/dev/null 2>&1 || {
    record_stage "models" "INCONCLUSIVE" 2 "curl missing"
    write_result "INCONCLUSIVE" "curl is required for --download-models." 2
    exit $?
  }
  mkdir -p "$MODEL_CACHE"
  [[ -n "$MODEL_08B" ]] || MODEL_08B="$MODEL_CACHE/Qwen3.5-0.8B-Q4_K_M.gguf"
  [[ -n "$MODEL_2B" ]] || MODEL_2B="$MODEL_CACHE/Qwen3.5-2B-Q4_K_M.gguf"
  if [[ ! -f "$MODEL_08B" ]]; then
    echo "Downloading exact Qwen3.5 0.8B reference model..."
    curl --fail --location --retry 3 --output "$MODEL_08B.part" "$QWEN35_08B_URL" && mv "$MODEL_08B.part" "$MODEL_08B"
    rc=$?
    if (( rc != 0 )); then
      rm -f "$MODEL_08B.part"
      record_stage "models" "INCONCLUSIVE" "$rc" "0.8B model download failed"
      write_result "INCONCLUSIVE" "Unable to obtain the exact 0.8B reference model." 2
      exit $?
    fi
  fi
  if [[ ! -f "$MODEL_2B" ]]; then
    echo "Downloading exact Qwen3.5 2B reference model..."
    curl --fail --location --retry 3 --output "$MODEL_2B.part" "$QWEN35_2B_URL" && mv "$MODEL_2B.part" "$MODEL_2B"
    rc=$?
    if (( rc != 0 )); then
      rm -f "$MODEL_2B.part"
      record_stage "models" "INCONCLUSIVE" "$rc" "2B model download failed"
      write_result "INCONCLUSIVE" "Unable to obtain the exact 2B reference model." 2
      exit $?
    fi
  fi
fi
for model in "$MODEL_08B" "$MODEL_2B"; do
  if [[ ! -f "$model" || ! -r "$model" ]]; then
    record_stage "models" "INCONCLUSIVE" 2 "missing or unreadable model: $model"
    write_result "INCONCLUSIVE" "A required curated GGUF is missing or unreadable." 2
    exit $?
  fi
done
record_stage "models" "PASS" 0 "both curated GGUF paths available; exact digests are verified by each side runner"

if [[ -z "$ARTIFACT_ROOT" ]]; then
  command -v gh >/dev/null 2>&1 || {
    record_stage "artifacts" "INCONCLUSIVE" 2 "gh CLI missing"
    write_result "INCONCLUSIVE" "GitHub CLI is required to download frozen CI artifacts; alternatively use --artifact-root." 2
    exit $?
  }
  if ! gh auth status >/dev/null 2>&1; then
    record_stage "artifacts" "INCONCLUSIVE" 2 "gh authentication unavailable"
    write_result "INCONCLUSIVE" "GitHub CLI is not authenticated. Run gh auth login or provide --artifact-root." 2
    exit $?
  fi
  ARTIFACT_ROOT="$RUN_DIR/artifacts"
  mkdir -p "$ARTIFACT_ROOT/$CONTROL_ARTIFACT" "$ARTIFACT_ROOT/$CANDIDATE_ARTIFACT"
  echo "Downloading frozen control APK pair..."
  run_logged "$LOG_DIR/download-control.log" gh run download "$ARTIFACT_RUN_ID" --repo "$REPO" --name "$CONTROL_ARTIFACT" --dir "$ARTIFACT_ROOT/$CONTROL_ARTIFACT"
  control_download_rc=$?
  echo "Downloading frozen candidate APK pair..."
  run_logged "$LOG_DIR/download-candidate.log" gh run download "$ARTIFACT_RUN_ID" --repo "$REPO" --name "$CANDIDATE_ARTIFACT" --dir "$ARTIFACT_ROOT/$CANDIDATE_ARTIFACT"
  candidate_download_rc=$?
  if (( control_download_rc != 0 || candidate_download_rc != 0 )); then
    record_stage "artifacts" "INCONCLUSIVE" 2 "GitHub Actions artifact download failed"
    write_result "INCONCLUSIVE" "Could not download both frozen CI-built APK pairs." 2
    exit $?
  fi
fi
ARTIFACT_ROOT="$(python3 - "$ARTIFACT_ROOT" <<'PY'
from pathlib import Path
import sys
print(Path(sys.argv[1]).expanduser().resolve())
PY
)"

one_file() {
  local root="$1" name="$2"
  local first="" second=""
  first="$(find "$root" -type f -name "$name" -print | head -n 1)"
  [[ -n "$first" ]] || return 1
  second="$(find "$root" -type f -name "$name" -print | sed -n '2p')"
  [[ -z "$second" ]] || { echo "Expected one $name under $root, found multiple" >&2; return 1; }
  printf '%s\n' "$first"
}

resolve_pair() {
  local root="$1" prefix="$2"
  local app test manifest
  app="$(one_file "$root" device-test-runner-debug.apk)" || return 1
  test="$(one_file "$root" device-test-runner-debug-androidTest.apk)" || return 1
  manifest="$(one_file "$root" build-manifest.json)" || return 1
  printf -v "${prefix}_APP" '%s' "$app"
  printf -v "${prefix}_TEST" '%s' "$test"
  printf -v "${prefix}_MANIFEST" '%s' "$manifest"
}

CONTROL_ROOT="$ARTIFACT_ROOT/$CONTROL_ARTIFACT"
CANDIDATE_ROOT="$ARTIFACT_ROOT/$CANDIDATE_ARTIFACT"
if [[ ! -d "$CONTROL_ROOT" ]]; then CONTROL_ROOT="$ARTIFACT_ROOT"; fi
if [[ ! -d "$CANDIDATE_ROOT" ]]; then CANDIDATE_ROOT="$ARTIFACT_ROOT"; fi
resolve_pair "$CONTROL_ROOT" CONTROL
control_pair_rc=$?
resolve_pair "$CANDIDATE_ROOT" CANDIDATE
candidate_pair_rc=$?
if (( control_pair_rc != 0 || candidate_pair_rc != 0 )); then
  record_stage "artifacts" "INCONCLUSIVE" 2 "APK pair or manifest could not be resolved uniquely"
  write_result "INCONCLUSIVE" "Downloaded artifact layout is incomplete or ambiguous." 2
  exit $?
fi
record_stage "artifacts" "PASS" 0 "control and candidate APK pairs resolved; side runners will verify exact manifests and SHA-256"

SIDE_COMMON=(
  --model-08b "$MODEL_08B"
  --model-2b "$MODEL_2B"
  --device "$DEVICE"
  --output-dir "$SIDE_OUTPUT"
  --thermal-start-max "$THERMAL_START_MAX"
  --timeout-seconds "$TIMEOUT_SECONDS"
  --warm-repetitions "$WARM_REPETITIONS"
  --load-repetitions "$LOAD_REPETITIONS"
  --memory-repeat "$MEMORY_REPEAT_COUNT"
  --max-pss-growth-kb "$MAX_PSS_GROWTH_KB"
)

echo
echo "== CONTROL side =="
run_logged "$LOG_DIR/control.log" bash "$ROOT_DIR/scripts/run-llup50-physical-side.sh" \
  --label control \
  --app-apk "$CONTROL_APP" \
  --test-apk "$CONTROL_TEST" \
  --package-manifest "$CONTROL_MANIFEST" \
  --expected-evidence-source "$CONTROL_EVIDENCE_SHA" \
  --runtime-source "$CONTROL_RUNTIME_SHA" \
  --backend-revision "$CONTROL_BACKEND_REVISION" \
  "${SIDE_COMMON[@]}"
control_rc=$?
if (( control_rc != 0 )); then
  record_stage "control" "INCONCLUSIVE" "$control_rc" "control baseline did not complete; candidate comparison is invalid"
  write_result "INCONCLUSIVE" "The frozen control side did not complete successfully, so the A/B cannot be interpreted." 2
  exit $?
fi
record_stage "control" "PASS" 0 "frozen b9637 control evidence captured"

echo
echo "== CANDIDATE side =="
run_logged "$LOG_DIR/candidate.log" bash "$ROOT_DIR/scripts/run-llup50-physical-side.sh" \
  --label candidate \
  --app-apk "$CANDIDATE_APP" \
  --test-apk "$CANDIDATE_TEST" \
  --package-manifest "$CANDIDATE_MANIFEST" \
  --expected-evidence-source "$CANDIDATE_EVIDENCE_SHA" \
  --runtime-source "$CANDIDATE_RUNTIME_SHA" \
  --backend-revision "$CANDIDATE_BACKEND_REVISION" \
  "${SIDE_COMMON[@]}"
candidate_rc=$?
if (( candidate_rc != 0 )); then
  if (( candidate_rc == 1 )); then
    record_stage "candidate" "FAIL" "$candidate_rc" "candidate instrumentation/lifecycle test failed after a valid control"
    write_result "FAIL" "The v0.3.0 candidate failed physical instrumentation or lifecycle evidence after the control passed." 1
  else
    record_stage "candidate" "INCONCLUSIVE" "$candidate_rc" "candidate environment/input validation failed"
    write_result "INCONCLUSIVE" "The candidate side could not be executed in a valid comparable environment." 2
  fi
  exit $?
fi
record_stage "candidate" "PASS" 0 "v0.3.0 candidate evidence captured"

CONTROL_MANIFEST_PATH="$(find "$SIDE_OUTPUT" -type f -path "*/control/$CONTROL_EVIDENCE_SHA/manifest.json" -print | head -n 1)"
CANDIDATE_MANIFEST_PATH="$(find "$SIDE_OUTPUT" -type f -path "*/candidate/$CANDIDATE_EVIDENCE_SHA/manifest.json" -print | head -n 1)"
CONTROL_DIR=""
CANDIDATE_DIR=""
[[ -n "$CONTROL_MANIFEST_PATH" ]] && CONTROL_DIR="$(dirname "$CONTROL_MANIFEST_PATH")"
[[ -n "$CANDIDATE_MANIFEST_PATH" ]] && CANDIDATE_DIR="$(dirname "$CANDIDATE_MANIFEST_PATH")"
if [[ -z "$CONTROL_DIR" || -z "$CANDIDATE_DIR" ]]; then
  record_stage "comparison" "INCONCLUSIVE" 2 "side manifests were not found at the expected frozen identities"
  write_result "INCONCLUSIVE" "Physical runs completed but their exact-identity manifests could not be located." 2
  exit $?
fi

run_logged "$LOG_DIR/comparison.log" python3 "$ROOT_DIR/scripts/compare-llup50-evidence.py" \
  --control "$CONTROL_DIR" --candidate "$CANDIDATE_DIR" --output-dir "$COMPARE_DIR"
compare_rc=$?
if (( compare_rc != 0 )); then
  record_stage "comparison" "INCONCLUSIVE" "$compare_rc" "canonical comparator rejected evidence identity/completeness"
  write_result "INCONCLUSIVE" "The A/B evidence is not comparable under the canonical identity contract." 2
  exit $?
fi
record_stage "comparison" "PASS" 0 "same-device identity contract and evidence completeness accepted; metrics report generated"

write_result "PASS" "Control and candidate completed on the same physical device and produced canonically comparable evidence. Review metric deltas before LLUP-70; no promotion threshold was applied." 0
exit $?
