#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd "$script_dir/.." && pwd -P)"
pin_file="${LLAMA_CPP_PIN_FILE:-$repo_root/backends/llama-cpp/llama-cpp-pin.json}"
submodule_path="${LLAMA_CPP_SUBMODULE_PATH:-$repo_root/third_party/llama.cpp}"
backend_runtime_file="${LLAMA_CPP_BACKEND_RUNTIME_FILE:-$repo_root/backends/llama-cpp/src/main/kotlin/io/github/daniele21/localllm/runtime/LlamaCppInferenceBackend.kt}"
qwen_runtime_tuning_file="${QWEN35_RUNTIME_TUNING_FILE:-$repo_root/models/model-profile/src/main/kotlin/io/github/daniele21/localllm/models/Qwen35RuntimeTuning.kt}"

print_initialization_help() {
  echo "llama.cpp submodule is not initialized at $submodule_path" >&2
  echo "Run: git submodule update --init --recursive third_party/llama.cpp" >&2
}

if [[ ! -f "$pin_file" ]]; then
  echo "llama.cpp pin manifest is missing at $pin_file" >&2
  exit 1
fi

mapfile -t pin_values < <(python3 - "$pin_file" <<'PY'
import json
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
try:
    payload = json.loads(path.read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError) as exc:
    raise SystemExit(f"invalid llama.cpp pin manifest: {exc}")

if payload.get("schema_version") != 1:
    raise SystemExit("invalid llama.cpp pin manifest: schema_version must be 1")

tag = payload.get("tag")
commit = payload.get("commit")
if not isinstance(tag, str) or not tag or any(ch.isspace() for ch in tag):
    raise SystemExit("invalid llama.cpp pin manifest: tag must be a non-empty token")
if not isinstance(commit, str) or re.fullmatch(r"[0-9a-f]{40}", commit) is None:
    raise SystemExit("invalid llama.cpp pin manifest: commit must be an exact lowercase 40-character SHA")
print(tag)
print(commit)
PY
)

if [[ "${#pin_values[@]}" -ne 2 ]]; then
  echo "invalid llama.cpp pin manifest: expected tag and commit" >&2
  exit 1
fi
expected_tag="${pin_values[0]}"
expected_commit="${pin_values[1]}"

if [[ ! -f "$backend_runtime_file" ]]; then
  echo "llama.cpp backend runtime is missing at $backend_runtime_file" >&2
  exit 1
fi

backend_runtime_revision="$(python3 - "$backend_runtime_file" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
try:
    content = path.read_text(encoding="utf-8")
except OSError as exc:
    raise SystemExit(f"unable to read llama.cpp backend runtime: {exc}")

matches = re.findall(
    r'override\s+val\s+revision\s*:\s*String\s*=\s*"([0-9a-f]{40})"',
    content,
)
if len(matches) != 1:
    raise SystemExit(
        "llama.cpp backend runtime must declare exactly one lowercase 40-character revision"
    )
print(matches[0])
PY
)"

if [[ "$backend_runtime_revision" != "$expected_commit" ]]; then
  echo "llama.cpp backend runtime revision mismatch: pin manifest expects $expected_commit ($expected_tag), backend declares $backend_runtime_revision" >&2
  exit 1
fi

if [[ ! -f "$qwen_runtime_tuning_file" ]]; then
  echo "Qwen3.5 runtime tuning policy is missing at $qwen_runtime_tuning_file" >&2
  exit 1
fi

qwen_runtime_revision="$(python3 - "$qwen_runtime_tuning_file" <<'PY'
from pathlib import Path
import re
import sys

path = Path(sys.argv[1])
try:
    content = path.read_text(encoding="utf-8")
except OSError as exc:
    raise SystemExit(f"unable to read Qwen3.5 runtime tuning policy: {exc}")

matches = re.findall(
    r'const\s+val\s+LLAMA_CPP_REVISION\s*=\s*"([0-9a-f]{40})"',
    content,
)
if len(matches) != 1:
    raise SystemExit(
        "Qwen3.5 runtime tuning policy must declare exactly one lowercase 40-character LLAMA_CPP_REVISION"
    )
print(matches[0])
PY
)"

if [[ "$qwen_runtime_revision" != "$expected_commit" ]]; then
  echo "Qwen3.5 runtime backend revision mismatch: pin manifest expects $expected_commit ($expected_tag), policy declares $qwen_runtime_revision" >&2
  exit 1
fi

if [[ ! -d "$submodule_path" || ! -e "$submodule_path/.git" ]]; then
  print_initialization_help
  exit 1
fi

expected_root="$(cd "$submodule_path" && pwd -P)"
actual_root="$(git -C "$submodule_path" rev-parse --show-toplevel 2>/dev/null || true)"

if [[ -z "$actual_root" || "$actual_root" != "$expected_root" ]]; then
  print_initialization_help
  exit 1
fi

actual_commit="$(git -C "$submodule_path" rev-parse HEAD)"

if [[ "$actual_commit" != "$expected_commit" ]]; then
  echo "llama.cpp pin mismatch: expected $expected_commit ($expected_tag), found $actual_commit" >&2
  exit 1
fi

if [[ -n "$(git -C "$submodule_path" status --porcelain)" ]]; then
  echo "llama.cpp submodule contains uncommitted changes" >&2
  exit 1
fi

echo "llama.cpp pin verified: $expected_tag ($actual_commit)"
