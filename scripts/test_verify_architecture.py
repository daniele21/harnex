#!/usr/bin/env python3

from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest import mock

import verify_architecture
from verify_architecture import (
    CppIncludeViolation,
    DependencyViolation,
    evaluate_cpp_include_violations,
    evaluate_dependency_violations,
    find_cpp_include_violations,
    find_dependency_violations,
)


class ArchitectureFitnessTest(unittest.TestCase):
    def test_core_backend_dependency_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "core" / "runtime-core"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text(
                'dependencies { implementation(project(":backends:llama-cpp")) }\n',
                encoding="utf-8",
            )
            self.assertEqual(
                find_dependency_violations(root, ("core:runtime-core",)),
                (DependencyViolation("core:runtime-core", "backends:llama-cpp", "implementation"),),
            )

    def test_test_dependency_does_not_violate_production_direction(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "core" / "runtime-core"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text(
                'dependencies { testImplementation(project(":backends:fake")) }\n',
                encoding="utf-8",
            )
            self.assertEqual(find_dependency_violations(root, ("core:runtime-core",)), ())

    def test_forbidden_edges_across_owners_are_detected(self) -> None:
        bad_edges = (
            ("core:runtime-core", "transports:android-binder-client"),
            ("transports:android-binder-client", "core:runtime-core"),
            ("transports:android-binder-contract", "models:model-profile"),
            ("integrations:android-service-host", "core:runtime-core"),
            ("models:model-store", "backends:llama-cpp"),
            ("observability:room-store", "transports:android-binder-client"),
            ("evaluation:engine", "integrations:android-service-host"),
            ("backends:llama-cpp", "core:runtime-core"),
            ("core:backend-spi", "apps:local-llm-phone-test"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source, target in bad_edges:
                module = root.joinpath(*source.split(":"))
                module.mkdir(parents=True)
                (module / "build.gradle.kts").write_text(
                    f'dependencies {{ implementation(project(":{target}")) }}\n',
                    encoding="utf-8",
                )
            violations = find_dependency_violations(root, (source for source, _ in bad_edges))
            self.assertEqual(
                {(violation.source_module, violation.target_module) for violation in violations},
                set(bad_edges),
            )

    def test_valid_current_ownership_edges_remain_allowed(self) -> None:
        valid_edges = (
            ("core:runtime-core", "models:model-store"),
            ("core:backend-spi", "models:model-profile"),
            ("transports:android-binder-client", "transports:android-binder-contract"),
            ("transports:in-process", "core:contracts"),
            ("integrations:android-service-host", "transports:android-binder-contract"),
            ("models:model-install", "models:model-catalog"),
            ("observability:health-engine", "models:model-store"),
            ("evaluation:runtime-adapter", "core:runtime-core"),
            ("backends:llama-cpp", "models:model-install"),
            ("apps:local-llm-phone-test", "backends:llama-cpp"),
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for source, target in valid_edges:
                module = root.joinpath(*source.split(":"))
                module.mkdir(parents=True)
                (module / "build.gradle.kts").write_text(
                    f'dependencies {{ api(project(":{target}")) }}\n',
                    encoding="utf-8",
                )
            self.assertEqual(find_dependency_violations(root, (source for source, _ in valid_edges)), ())

    def test_production_rule_does_not_scan_test_only_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "transports" / "android-binder-client"
            module.mkdir(parents=True)
            (module / "build.gradle.kts").write_text(
                'testImplementation(project(":core:runtime-core"))\n',
                encoding="utf-8",
            )
            self.assertEqual(find_dependency_violations(root, ("transports:android-binder-client",)), ())

    def test_any_core_backend_dependency_fails_without_exception(self) -> None:
        violation = DependencyViolation("core:runtime-core", "backends:llama-cpp", "implementation")
        messages = evaluate_dependency_violations((violation,))
        self.assertTrue(any("core:runtime-core -> backends:llama-cpp" in message for message in messages))

    def test_empty_dependency_exception_set_has_no_stale_debt(self) -> None:
        self.assertEqual(evaluate_dependency_violations(()), ())

    def test_cpp_implementation_include_is_detected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "native" / "entry.cpp"
            source.parent.mkdir(parents=True)
            source.write_text('#include "implementation.cpp"\n', encoding="utf-8")
            self.assertEqual(
                find_cpp_include_violations(root),
                (CppIncludeViolation("native/entry.cpp", "implementation.cpp"),),
            )

    def test_unexpected_cpp_include_fails(self) -> None:
        violation = CppIncludeViolation("native/entry.cpp", "implementation.cpp")
        messages = evaluate_cpp_include_violations((violation,))
        self.assertTrue(any("implementation .cpp include is forbidden" in message for message in messages))

    def test_empty_cpp_exception_set_has_no_stale_debt(self) -> None:
        self.assertEqual(evaluate_cpp_include_violations(()), ())

    def test_third_party_cpp_is_not_scanned(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "third_party" / "vendor" / "entry.cpp"
            source.parent.mkdir(parents=True)
            source.write_text('#include "implementation.cpp"\n', encoding="utf-8")
            self.assertEqual(find_cpp_include_violations(root), ())

    def test_evaluators_can_be_used_with_explicit_exception_sets(self) -> None:
        dependency = DependencyViolation("core:a", "backends:b", "implementation")
        cpp = CppIncludeViolation("native/a.cpp", "b.cpp")
        with mock.patch.object(verify_architecture, "KNOWN_DEPENDENCY_EXCEPTIONS", {}), mock.patch.object(
            verify_architecture, "KNOWN_CPP_INCLUDE_EXCEPTIONS", {}
        ):
            self.assertEqual(len(evaluate_dependency_violations((dependency,))), 1)
            self.assertEqual(len(evaluate_cpp_include_violations((cpp,))), 1)


if __name__ == "__main__":
    unittest.main()
