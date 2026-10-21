#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
verifier="$script_dir/verify-llama-cpp-pin.sh"
temporary_root="$(mktemp -d)"
trap 'rm -rf "$temporary_root"' EXIT

write_runtime_revision() {
  local repository_path="$1"
  local commit="$2"
  local policy_path="$repository_path/models/model-profile/src/main/kotlin/io/github/daniele21/localllm/models/Qwen35RuntimeTuning.kt"
  mkdir -p "$(dirname "$policy_path")"
  cat > "$policy_path" <<EOF
package io.github.daniele21.localllm.models

object Qwen35RuntimeTuningProfiles {
    const val LLAMA_CPP_REVISION = "$commit"
}
EOF
}

write_backend_revision() {
  local repository_path="$1"
  local commit="$2"
  local backend_path="$repository_path/backends/llama-cpp/src/main/kotlin/io/github/daniele21/localllm/runtime/LlamaCppInferenceBackend.kt"
  mkdir -p "$(dirname "$backend_path")"
  cat > "$backend_path" <<EOF
package io.github.daniele21.localllm.runtime

class LlamaCppInferenceBackend {
    val id: String = "llama.cpp"
    override val revision: String = "$commit"
}
EOF
}

write_pin() {
  local repository_path="$1"
  local commit="$2"
  local tag="${3:-test-pin}"
  mkdir -p "$repository_path/backends/llama-cpp"
  cat > "$repository_path/backends/llama-cpp/llama-cpp-pin.json" <<EOF
{
  "schema_version": 1,
  "tag": "$tag",
  "commit": "$commit"
}
EOF
  write_backend_revision "$repository_path" "$commit"
  write_runtime_revision "$repository_path" "$commit"
}

run_verifier() {
  local repository_path="$1"
  LLAMA_CPP_PIN_FILE="$repository_path/backends/llama-cpp/llama-cpp-pin.json" \
  LLAMA_CPP_SUBMODULE_PATH="$repository_path/third_party/llama.cpp" \
  LLAMA_CPP_BACKEND_RUNTIME_FILE="$repository_path/backends/llama-cpp/src/main/kotlin/io/github/daniele21/localllm/runtime/LlamaCppInferenceBackend.kt" \
  QWEN35_RUNTIME_TUNING_FILE="$repository_path/models/model-profile/src/main/kotlin/io/github/daniele21/localllm/models/Qwen35RuntimeTuning.kt" \
    bash "$verifier"
}

assert_uninitialized_failure() {
  local case_name="$1"
  local repository_path="$2"
  local stderr_file="$temporary_root/$case_name.stderr"

  write_pin "$repository_path" "0000000000000000000000000000000000000000"
  if run_verifier "$repository_path" > /dev/null 2>"$stderr_file"; then
    echo "$case_name unexpectedly passed" >&2
    exit 1
  fi

  if ! grep -Fq "llama.cpp submodule is not initialized" "$stderr_file"; then
    echo "$case_name did not report the missing submodule clearly" >&2
    cat "$stderr_file" >&2
    exit 1
  fi

  if grep -Fq "pin mismatch" "$stderr_file"; then
    echo "$case_name reported a false pin mismatch" >&2
    cat "$stderr_file" >&2
    exit 1
  fi
}

create_initialized_submodule() {
  local repository_path="$1"
  mkdir -p "$repository_path/third_party/llama.cpp"
  git -C "$repository_path/third_party/llama.cpp" init -q
  git -C "$repository_path/third_party/llama.cpp" config user.email "llup-test@example.invalid"
  git -C "$repository_path/third_party/llama.cpp" config user.name "LLUP test"
  printf 'fixture\n' > "$repository_path/third_party/llama.cpp/fixture.txt"
  git -C "$repository_path/third_party/llama.cpp" add fixture.txt
  git -C "$repository_path/third_party/llama.cpp" commit -q -m fixture
}

missing_path_repository="$temporary_root/missing-path"
mkdir -p "$missing_path_repository"
git -C "$missing_path_repository" init -q
assert_uninitialized_failure "missing-path" "$missing_path_repository"

empty_path_repository="$temporary_root/empty-path"
mkdir -p "$empty_path_repository/third_party/llama.cpp"
git -C "$empty_path_repository" init -q
assert_uninitialized_failure "empty-path" "$empty_path_repository"

matching_repository="$temporary_root/matching"
create_initialized_submodule "$matching_repository"
matching_commit="$(git -C "$matching_repository/third_party/llama.cpp" rev-parse HEAD)"
write_pin "$matching_repository" "$matching_commit" "fixture-tag"
matching_output="$(run_verifier "$matching_repository")"
if ! grep -Fq "fixture-tag ($matching_commit)" <<<"$matching_output"; then
  echo "matching pin did not report canonical tag and commit" >&2
  echo "$matching_output" >&2
  exit 1
fi

backend_revision_mismatch_repository="$temporary_root/backend-revision-mismatch"
create_initialized_submodule "$backend_revision_mismatch_repository"
backend_revision_commit="$(git -C "$backend_revision_mismatch_repository/third_party/llama.cpp" rev-parse HEAD)"
write_pin "$backend_revision_mismatch_repository" "$backend_revision_commit" "backend-revision-pin"
write_backend_revision "$backend_revision_mismatch_repository" "0000000000000000000000000000000000000000"
backend_revision_stderr="$temporary_root/backend-revision-mismatch.stderr"
if run_verifier "$backend_revision_mismatch_repository" > /dev/null 2>"$backend_revision_stderr"; then
  echo "backend revision mismatch unexpectedly passed" >&2
  exit 1
fi
if ! grep -Fq "llama.cpp backend runtime revision mismatch" "$backend_revision_stderr"; then
  echo "backend revision mismatch did not fail closed on execution identity drift" >&2
  cat "$backend_revision_stderr" >&2
  exit 1
fi

runtime_revision_mismatch_repository="$temporary_root/runtime-revision-mismatch"
create_initialized_submodule "$runtime_revision_mismatch_repository"
runtime_revision_commit="$(git -C "$runtime_revision_mismatch_repository/third_party/llama.cpp" rev-parse HEAD)"
write_pin "$runtime_revision_mismatch_repository" "$runtime_revision_commit" "runtime-revision-pin"
write_runtime_revision "$runtime_revision_mismatch_repository" "0000000000000000000000000000000000000000"
runtime_revision_stderr="$temporary_root/runtime-revision-mismatch.stderr"
if run_verifier "$runtime_revision_mismatch_repository" > /dev/null 2>"$runtime_revision_stderr"; then
  echo "runtime revision mismatch unexpectedly passed" >&2
  exit 1
fi
if ! grep -Fq "Qwen3.5 runtime backend revision mismatch" "$runtime_revision_stderr"; then
  echo "runtime revision mismatch did not fail closed on policy drift" >&2
  cat "$runtime_revision_stderr" >&2
  exit 1
fi

mismatch_repository="$temporary_root/mismatch"
create_initialized_submodule "$mismatch_repository"
write_pin "$mismatch_repository" "0000000000000000000000000000000000000000" "wrong-pin"
mismatch_stderr="$temporary_root/mismatch.stderr"
if run_verifier "$mismatch_repository" > /dev/null 2>"$mismatch_stderr"; then
  echo "mismatch unexpectedly passed" >&2
  exit 1
fi
if ! grep -Fq "pin mismatch" "$mismatch_stderr"; then
  echo "mismatch did not fail closed with a pin mismatch" >&2
  cat "$mismatch_stderr" >&2
  exit 1
fi

invalid_manifest_repository="$temporary_root/invalid-manifest"
create_initialized_submodule "$invalid_manifest_repository"
mkdir -p "$invalid_manifest_repository/backends/llama-cpp"
printf '{"schema_version":1,"tag":"bad tag","commit":"nope"}\n' > \
  "$invalid_manifest_repository/backends/llama-cpp/llama-cpp-pin.json"
write_runtime_revision "$invalid_manifest_repository" "0000000000000000000000000000000000000000"
invalid_stderr="$temporary_root/invalid.stderr"
if run_verifier "$invalid_manifest_repository" > /dev/null 2>"$invalid_stderr"; then
  echo "invalid manifest unexpectedly passed" >&2
  exit 1
fi
if ! grep -Fq "invalid llama.cpp pin manifest" "$invalid_stderr"; then
  echo "invalid manifest did not report a manifest failure" >&2
  cat "$invalid_stderr" >&2
  exit 1
fi

dirty_repository="$temporary_root/dirty"
create_initialized_submodule "$dirty_repository"
dirty_commit="$(git -C "$dirty_repository/third_party/llama.cpp" rev-parse HEAD)"
write_pin "$dirty_repository" "$dirty_commit"
printf 'dirty\n' >> "$dirty_repository/third_party/llama.cpp/fixture.txt"
dirty_stderr="$temporary_root/dirty.stderr"
if run_verifier "$dirty_repository" > /dev/null 2>"$dirty_stderr"; then
  echo "dirty submodule unexpectedly passed" >&2
  exit 1
fi
if ! grep -Fq "uncommitted changes" "$dirty_stderr"; then
  echo "dirty submodule did not fail closed" >&2
  cat "$dirty_stderr" >&2
  exit 1
fi

echo "verify-llama-cpp-pin regression tests passed"
