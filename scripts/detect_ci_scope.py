#!/usr/bin/env python3
"""Determine Harnex risk dimensions, required gates and validation-profile shorthand."""
from __future__ import annotations
import argparse, os, subprocess, sys
from dataclasses import dataclass, replace
from pathlib import PurePosixPath
from typing import Iterable, Sequence
from gradle_module_inventory import load_gradle_modules

ZERO_SHA = "0" * 40
PROFILE_RANK = {"lean": 0, "scoped": 1, "strong": 2, "full": 3}
STAGES = ("iteration", "integration", "release")
DOC_ONLY_PATHS = {
    ".engineering/baseline.json", ".engineering/documentation-policy.json", ".github/CODEOWNERS",
    ".github/dependabot.yml", ".gitattributes", ".gitignore", "AGENTS.md", "CODE_OF_CONDUCT.md",
    "CONTRIBUTING.md", "EXECUTION-CAPABILITY-CONTRACT.md", "E2E-ENVIRONMENT-CONTRACT.md", "LICENSE",
    "NOTICE", "README.md", "SECURITY.md", "design/brand-kit.json", "design/ux-contract.json",
}
DOC_ONLY_PREFIXES = ("docs/", "skills/")
DOC_ONLY_SUFFIXES = (".md", ".mdx", ".rst")
FORCE_ALL_PATHS = {
    ".engineering/commands.json", ".github/workflows/package.yml", ".github/workflows/validate.yml",
    ".github/workflows/remote-preflight.yml", "scripts/detect_ci_scope.py", "scripts/gradle_module_inventory.py",
    "scripts/test_detect_ci_scope.py", "scripts/test_gradle_module_inventory.py",
}
NATIVE_PATHS = {".gitmodules", "third_party/llama.cpp", "scripts/test-verify-llama-cpp-pin.sh", "scripts/verify-android-packaging.py", "scripts/verify-llama-cpp-pin.sh"}
NATIVE_PREFIXES = ("backends/llama-cpp/", "third_party/llama.cpp/")
NATIVE_SUFFIXES = (".c", ".cc", ".cmake", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx")
PACKAGING_PATHS = {".gitmodules", "build.gradle.kts", "gradle.properties", "settings.gradle.kts", "scripts/verify-android-packaging.py", "third_party/llama.cpp"}
PACKAGING_PREFIXES = ("apps/", "backends/llama-cpp/", "gradle/", "third_party/llama.cpp/")
PACKAGE_BOUNDARY_PREFIXES = ("transports/android-binder-contract/", "transports/android-binder-client/", "integrations/android-service-host/", "apps/shared-runtime-client-consumer-fixture/")
PACKAGING_SUFFIXES = (".gradle", ".gradle.kts")
GRADLE_MODULES = load_gradle_modules()
MODULE_PREFIXES = tuple((module.replace(":", "/") + "/", module) for module in GRADLE_MODULES)
GLOBAL_GRADLE_PATHS = {"build.gradle.kts", "gradle.properties", "settings.gradle.kts", "gradle/libs.versions.toml"}
GLOBAL_GRADLE_PREFIXES = ("build-logic/", "gradle/")
PUBLIC_CONTRACT_PREFIXES = ("core/contracts/", "evaluation/contracts/", "observability/contracts/")
CROSS_BOUNDARY_PREFIXES = ("core/backend-spi/", "core/runtime-core/", "models/model-store/", "transports/android-binder-contract/", "transports/android-binder-client/", "integrations/android-service-host/", "apps/shared-runtime-client-consumer-fixture/", "evaluation/room-store/", "observability/room-store/")

EMBEDDED_SAMPLE = "samples/external-embedded-android/"
EMBEDDED_SOURCE_PREFIXES = tuple(
    f"{EMBEDDED_SAMPLE}app/src/{source_set}/{language}/"
    for source_set in ("main", "debug", "test") for language in ("kotlin", "java")
)
EMBEDDED_INPUT_PREFIXES = (
    EMBEDDED_SAMPLE, "integrations/android-embedded-sdk/", "core/contracts/", "core/backend-spi/",
    "core/runtime-core/", "models/model-profile/", "models/model-store/", "models/model-catalog/",
    "models/model-download/", "models/model-install/", "observability/contracts/",
    "transports/in-process/", "backends/llama-cpp/",
)
EMBEDDED_INPUT_FILES = {
    ".github/workflows/embedded-sdk.yml", "scripts/verify-embedded-sdk-publication.sh",
    "scripts/verify-embedded-smoke-ui-emulator.sh",
}


@dataclass(frozen=True)
class ValidationScope:
    profile: str
    android: bool
    native: bool
    packaging: bool
    modules: tuple[str, ...]
    reason: str
    risk_dimensions: tuple[str, ...] = ()
    required_gates: tuple[str, ...] = ()
    embedded_consumer: bool = False


def normalize_path(path: str) -> str: return str(PurePosixPath(path.strip().replace("\\", "/")))
def is_docs_only(path: str) -> bool: return path in DOC_ONLY_PATHS or path.startswith(DOC_ONLY_PREFIXES) or path.endswith(DOC_ONLY_SUFFIXES)
def affects_native(path: str) -> bool:
    return path in NATIVE_PATHS or path.startswith(NATIVE_PREFIXES) or path.endswith(NATIVE_SUFFIXES) or PurePosixPath(path).name == "CMakeLists.txt"
def affects_packaging(path: str) -> bool:
    name=PurePosixPath(path).name
    return affects_native(path) or path in PACKAGING_PATHS or path.startswith(PACKAGING_PREFIXES) or path.startswith(PACKAGE_BOUNDARY_PREFIXES) or path.endswith(PACKAGING_SUFFIXES) or name == "AndroidManifest.xml" or name.startswith("proguard")
def is_global_build_path(path: str) -> bool: return path in GLOBAL_GRADLE_PATHS or path.startswith(GLOBAL_GRADLE_PREFIXES)
def is_release_sensitive(path: str) -> bool:
    name=PurePosixPath(path).name
    return affects_native(path) or path.startswith(PUBLIC_CONTRACT_PREFIXES) or path.startswith(CROSS_BOUNDARY_PREFIXES) or path.endswith(PACKAGING_SUFFIXES) or name == "AndroidManifest.xml" or name.startswith("proguard")


def affected_gradle_modules(paths: Sequence[str]) -> tuple[str, ...]:
    implementation=tuple(path for path in paths if not is_docs_only(path))
    if not implementation: return ()
    if any(path.startswith(PUBLIC_CONTRACT_PREFIXES) for path in implementation): return ("all",)
    if any(path in FORCE_ALL_PATHS or is_global_build_path(path) for path in implementation): return ("all",)
    modules=set(); unresolved=[]
    for path in implementation:
        for prefix, module in MODULE_PREFIXES:
            root=prefix.removesuffix("/")
            if path == root or path.startswith(prefix): modules.add(module); break
        else:
            if path == "third_party/llama.cpp" or path.startswith("third_party/llama.cpp/") or path in NATIVE_PATHS: modules.add("backends:llama-cpp")
            elif path.startswith((".engineering/", "skills/", "docs/", "design/")) or path in DOC_ONLY_PATHS: pass
            else: unresolved.append(path)
    if unresolved: return ("all",)
    return tuple(module for module in GRADLE_MODULES if module in modules)


def classify_paths(paths: Iterable[str], *, force_all: bool=False) -> ValidationScope:
    normalized=tuple(sorted({normalize_path(path) for path in paths if path.strip()}))
    if force_all:
        return ValidationScope("full", True, True, True, ("all",), "explicit full validation",
                               ("release_or_global",), embedded_consumer=True)
    if not normalized:
        return ValidationScope("full", True, True, True, ("all",), "no reliable diff available",
                               ("unknown_scope",), embedded_consumer=True)
    embedded = any(
        path in EMBEDDED_INPUT_FILES or path.startswith(EMBEDDED_INPUT_PREFIXES)
        or path in NATIVE_PATHS or is_global_build_path(path) or path in FORCE_ALL_PATHS
        for path in normalized
    )
    # Only managed Kotlin/Java sources belong exclusively to the standalone Gradle build.
    # JNI, manifests, build logic and unfamiliar paths retain conservative routing.
    root_paths = tuple(path for path in normalized if not (
        path.startswith(EMBEDDED_SOURCE_PREFIXES) and path.endswith((".kt", ".java"))
    ))
    if len(root_paths) < len(normalized) and all(is_docs_only(path) for path in root_paths):
        return ValidationScope("scoped", False, False, False, (), "external embedded Android sample sources",
                               ("embedded_consumer",), embedded_consumer=True)
    return replace(classify_root_paths(root_paths), embedded_consumer=embedded)


def classify_root_paths(normalized: Sequence[str]) -> ValidationScope:
    if any(path in FORCE_ALL_PATHS for path in normalized): return ValidationScope("full", True, True, True, ("all",), "validation selector or workflow changed", ("validation_routing",))
    if any(is_global_build_path(path) for path in normalized): return ValidationScope("full", True, True, True, ("all",), "global Gradle/toolchain or dependency inventory changed", ("global_build",))
    implementation=tuple(path for path in normalized if not is_docs_only(path))
    if not implementation: return ValidationScope("lean", False, False, False, (), "documentation or repository metadata only", ("governance",))
    native=any(affects_native(path) for path in implementation); packaging=any(affects_packaging(path) for path in implementation); modules=affected_gradle_modules(normalized)
    public_contract=any(path.startswith(PUBLIC_CONTRACT_PREFIXES) for path in implementation); cross_boundary=any(path.startswith(CROSS_BOUNDARY_PREFIXES) for path in implementation); release_sensitive=any(is_release_sensitive(path) for path in implementation)
    risks=[]
    if native: risks.append("native_jni")
    if public_contract: risks.append("public_contract")
    if cross_boundary: risks.append("cross_boundary_runtime")
    if packaging: risks.append("packaging")
    if not risks: risks.append("contained_android_module")
    if modules == ("all",) and not public_contract: return ValidationScope("full", True, native, packaging, modules, "unknown or repository-wide executable scope", tuple(risks + ["unknown_scope"]))
    if native: profile, reason="strong", "native or JNI inputs changed"
    elif public_contract: profile, reason="strong", "shared public contract changed"
    elif cross_boundary: profile, reason="strong", "runtime, Binder, consumer, persistence or cross-boundary owner changed"
    elif release_sensitive: profile, reason="strong", "release-sensitive Android build/manifest/package input changed"
    else: profile, reason="scoped", "contained Android implementation change"
    if not modules: return ValidationScope("full", True, native, packaging, ("all",), "executable change has no trustworthy module mapping", tuple(risks + ["unknown_scope"]))
    return ValidationScope(profile, True, native, packaging, modules, reason, tuple(risks))


def gates_for(scope: ValidationScope, stage: str) -> tuple[str, ...]:
    gates=["repository-guards"]
    if scope.embedded_consumer and stage != "iteration": gates.append("embedded-consumer")
    if not scope.android: return tuple(gates)
    gates += ["format-static", "affected-compile", "affected-unit"]
    if scope.profile in {"strong", "full"}: gates.append("direct-contract")
    if stage in {"integration", "release"}: gates += ["affected-lint", "direct-consumer-compile"]
    if stage in {"integration", "release"} and scope.native: gates.append("native-host")
    if stage in {"integration", "release"} and scope.packaging and scope.profile in {"strong", "full"}: gates.append("android-packaging")
    if scope.profile == "full": gates += ["all-module-validation"]
    if stage == "release": gates += ["release-critical"]
    return tuple(dict.fromkeys(gates))


def apply_requested_profile(scope: ValidationScope, requested: str) -> ValidationScope:
    requested=requested.lower()
    if requested == "auto": return scope
    if requested not in {"strong", "full"}: raise ValueError("requested profile must be auto, strong or full")
    if PROFILE_RANK[requested] <= PROFILE_RANK[scope.profile]: return scope
    if requested == "full": return replace(scope, profile="full", android=True, native=True, packaging=True, embedded_consumer=True, modules=("all",), reason=f"explicit full override; auto={scope.profile}: {scope.reason}", risk_dimensions=tuple(dict.fromkeys(scope.risk_dimensions + ("explicit_full",))))
    return replace(scope, profile="strong", reason=f"explicit strong override; auto={scope.profile}: {scope.reason}", risk_dimensions=tuple(dict.fromkeys(scope.risk_dimensions + ("explicit_strong",))))


def adjust_for_event(scope: ValidationScope, event_name: str) -> ValidationScope:
    if event_name == "push" and scope.packaging and scope.profile != "full": return replace(scope, packaging=False, reason=f"{scope.reason}; packaging delegated to package workflow on push")
    return scope

def ensure_commit_available(sha: str) -> None:
    if not sha or sha == ZERO_SHA: raise ValueError("missing or unusable comparison SHA")
    present=subprocess.run(["git", "cat-file", "-e", f"{sha}^{{commit}}"], check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if present.returncode != 0: subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", sha], check=True)
def git_changed_files(base_sha: str, head_sha: str) -> Sequence[str]:
    ensure_commit_available(base_sha); ensure_commit_available(head_sha)
    result=subprocess.run(["git", "diff", "--name-only", "--no-renames", "--diff-filter=ACMRDT", base_sha, head_sha], check=True, capture_output=True, text=True)
    return tuple(line for line in result.stdout.splitlines() if line.strip())
def write_outputs(path: str, scope: ValidationScope, stage: str) -> None:
    with open(path, "a", encoding="utf-8") as output:
        for key, value in (
            ("stage", stage), ("profile", scope.profile), ("android", str(scope.android).lower()), ("native", str(scope.native).lower()),
            ("packaging", str(scope.packaging).lower()), ("modules", ",".join(scope.modules)), ("risk_dimensions", ",".join(scope.risk_dimensions)),
            ("required_gates", ",".join(scope.required_gates)), ("reason", scope.reason),
        ): output.write(f"{key}={value}\n")
def append_step_summary(paths: Sequence[str], scope: ValidationScope, stage: str) -> None:
    summary_path=os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path: return
    with open(summary_path, "a", encoding="utf-8") as summary:
        summary.write("## Validation scope\n\n")
        summary.write(f"- Stage: **{stage.upper()}**\n- Profile: **{scope.profile.upper()}**\n")
        summary.write(f"- Risks: `{', '.join(scope.risk_dimensions) or 'none'}`\n- Required gates: `{', '.join(scope.required_gates) or 'none'}`\n")
        summary.write(f"- Modules: `{', '.join(scope.modules) or 'none'}`\n- Reason: {scope.reason}\n- Changed paths considered: {len(paths)}\n")

def parse_args() -> argparse.Namespace:
    parser=argparse.ArgumentParser(); parser.add_argument("--event", required=True); parser.add_argument("--base-sha", default=""); parser.add_argument("--head-sha", default=""); parser.add_argument("--github-output", required=True); parser.add_argument("--profile", default="auto", choices=("auto", "strong", "full")); parser.add_argument("--stage", default="integration", choices=STAGES); parser.add_argument("--force-full", action="store_true"); return parser.parse_args()
def main() -> int:
    args=parse_args(); paths: Sequence[str]=()
    try:
        if args.force_full: scope=classify_paths((), force_all=True)
        else:
            paths=git_changed_files(args.base_sha, args.head_sha); scope=classify_paths(paths); scope=apply_requested_profile(scope, args.profile); scope=adjust_for_event(scope, args.event)
        scope=replace(scope, required_gates=gates_for(scope, args.stage))
    except (ValueError, subprocess.CalledProcessError) as exc:
        print(f"warning: unable to determine safe validation scope: {exc}", file=sys.stderr); scope=classify_paths((), force_all=True); scope=replace(scope, required_gates=gates_for(scope, args.stage))
    write_outputs(args.github_output, scope, args.stage); append_step_summary(paths, scope, args.stage)
    print(f"validation scope: stage={args.stage} profile={scope.profile} risks={','.join(scope.risk_dimensions)} gates={','.join(scope.required_gates)} modules={','.join(scope.modules)} reason={scope.reason}")
    if paths:
        print("changed paths:")
        for path in paths: print(f"  - {path}")
    return 0
if __name__ == "__main__": raise SystemExit(main())
