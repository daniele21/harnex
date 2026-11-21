#!/usr/bin/env python3

from __future__ import annotations

import unittest

from detect_ci_scope import adjust_for_event, apply_requested_profile, classify_paths, gates_for
from test_development_velocity import DevelopmentVelocityTest  # imported for unittest discovery


class DetectCiScopeTest(unittest.TestCase):
    sample_controller = (
        "samples/external-embedded-android/app/src/main/kotlin/"
        "io/github/daniele21/localllm/externalembedded/EmbeddedChatController.kt"
    )

    def test_external_sample_routes_to_its_own_consumer_proof(self) -> None:
        scope = classify_paths([self.sample_controller, "docs/architecture.md"])
        self.assertEqual(scope.profile, "scoped")
        self.assertFalse(scope.android)
        self.assertEqual(scope.modules, ())
        self.assertEqual(gates_for(scope, "integration"), ("repository-guards", "embedded-consumer"))
        self.assertNotIn("embedded-consumer", gates_for(scope, "iteration"))

    def test_sample_does_not_weaken_other_changed_owners(self) -> None:
        for path, profile, modules in (
            ("core/runtime-core/src/main/kotlin/Runtime.kt", "strong", ("core:runtime-core",)),
            ("core/contracts/src/main/kotlin/Request.kt", "strong", ("all",)),
            ("gradle/libs.versions.toml", "full", ("all",)),
            ("future-module/unknown.sh", "full", ("all",)),
            ("samples/external-embedded-android/app/src/main/cpp/whisper.cpp", "full", ("all",)),
            ("samples/external-embedded-android/app/src/main/AndroidManifest.xml", "full", ("all",)),
        ):
            with self.subTest(path=path):
                scope = classify_paths([self.sample_controller, path])
                self.assertEqual(scope.profile, profile)
                self.assertEqual(scope.modules, modules)
                self.assertTrue(scope.android)
                self.assertIn("embedded-consumer", gates_for(scope, "integration"))
                if path.endswith(".cpp"):
                    self.assertIn("native-host", gates_for(scope, "integration"))

    def test_embedded_proof_on_full_override_but_not_unrelated_module(self) -> None:
        scope = classify_paths(["evaluation/evaluators/src/test/kotlin/EvaluatorTest.kt"])
        self.assertNotIn("embedded-consumer", gates_for(scope, "integration"))
        self.assertIn("embedded-consumer", gates_for(apply_requested_profile(scope, "full"), "integration"))

    def test_documentation_only_is_lean_and_skips_expensive_jobs(self) -> None:
        scope = classify_paths(["README.md", "docs/architecture.md"])
        self.assertEqual(scope.profile, "lean")
        self.assertFalse(scope.android)
        self.assertFalse(scope.native)
        self.assertFalse(scope.packaging)
        self.assertEqual(scope.modules, ())

    def test_governance_only_is_lean(self) -> None:
        scope = classify_paths([".engineering/baseline.json", "AGENTS.md"])
        self.assertEqual(scope.profile, "lean")
        self.assertFalse(scope.android)
        self.assertEqual(scope.modules, ())

    def test_dependabot_configuration_is_metadata_only(self) -> None:
        scope = classify_paths([".github/dependabot.yml"])
        self.assertEqual(scope.profile, "lean")
        self.assertFalse(scope.android)

    def test_contained_model_download_change_is_scoped(self) -> None:
        scope = classify_paths(["models/model-download/src/main/kotlin/Download.kt"])
        self.assertEqual(scope.profile, "scoped")
        self.assertTrue(scope.android)
        self.assertFalse(scope.native)
        self.assertFalse(scope.packaging)
        self.assertEqual(scope.modules, ("models:model-download",))

    def test_runtime_core_change_is_strong_boundary(self) -> None:
        scope = classify_paths(["core/runtime-core/src/main/kotlin/Runtime.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertFalse(scope.packaging)
        self.assertEqual(scope.modules, ("core:runtime-core",))

    def test_backend_spi_change_is_strong_boundary(self) -> None:
        scope = classify_paths(["core/backend-spi/src/main/kotlin/Backend.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("core:backend-spi",))

    def test_evaluator_change_selects_only_evaluator_module(self) -> None:
        scope = classify_paths(["evaluation/evaluators/src/main/kotlin/ExactMatchEvaluator.kt"])
        self.assertEqual(scope.profile, "scoped")
        self.assertEqual(scope.modules, ("evaluation:evaluators",))

    def test_evaluation_engine_change_selects_only_engine_module(self) -> None:
        scope = classify_paths(["evaluation/engine/src/main/kotlin/EvaluationEngine.kt"])
        self.assertEqual(scope.profile, "scoped")
        self.assertEqual(scope.modules, ("evaluation:engine",))

    def test_evaluation_memory_store_change_selects_only_store_module(self) -> None:
        scope = classify_paths(["evaluation/in-memory-store/src/main/kotlin/InMemoryEvaluationResultRepository.kt"])
        self.assertEqual(scope.profile, "scoped")
        self.assertEqual(scope.modules, ("evaluation:in-memory-store",))

    def test_room_store_is_strong_persistence_boundary(self) -> None:
        scope = classify_paths(["evaluation/room-store/src/main/kotlin/EvaluationRoomEntities.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("evaluation:room-store",))

    def test_model_distribution_modules_are_selected_explicitly(self) -> None:
        scope = classify_paths([
            "models/model-catalog/src/main/kotlin/Catalog.kt",
            "models/model-download/src/main/kotlin/Download.kt",
            "models/model-install/src/main/kotlin/Install.kt",
        ])
        self.assertEqual(scope.profile, "scoped")
        self.assertEqual(scope.modules, ("models:model-catalog", "models:model-download", "models:model-install"))

    def test_model_store_is_strong_lifecycle_boundary(self) -> None:
        scope = classify_paths(["models/model-store/src/main/kotlin/ModelStore.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("models:model-store",))

    def test_binder_contract_change_is_strong_and_packaging_sensitive(self) -> None:
        scope = classify_paths(["transports/android-binder-contract/src/main/kotlin/ProtocolModels.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.android)
        self.assertFalse(scope.native)
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("transports:android-binder-contract",))

    def test_binder_contract_build_change_is_strong_and_packaging_sensitive(self) -> None:
        scope = classify_paths(["transports/android-binder-contract/build.gradle.kts"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("transports:android-binder-contract",))

    def test_host_service_change_is_strong_and_packaging_sensitive(self) -> None:
        scope = classify_paths(["integrations/android-service-host/src/main/kotlin/CallerAuthorization.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("integrations:android-service-host",))

    def test_consumer_fixture_change_is_strong_and_packaging_sensitive(self) -> None:
        scope = classify_paths(["apps/shared-runtime-client-consumer-fixture/src/main/kotlin/Fixture.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("apps:shared-runtime-client-consumer-fixture",))

    def test_normal_app_kotlin_change_stays_scoped(self) -> None:
        scope = classify_paths(["apps/local-llm-phone-test/src/main/kotlin/MainActivity.kt"])
        self.assertEqual(scope.profile, "scoped")
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("apps:local-llm-phone-test",))

    def test_manifest_change_is_strong(self) -> None:
        scope = classify_paths(["apps/local-llm-console/src/main/AndroidManifest.xml"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("apps:local-llm-console",))

    def test_mixed_scoped_modules_preserve_repository_order(self) -> None:
        scope = classify_paths([
            "observability/health-engine/src/main/kotlin/Health.kt",
            "evaluation/evaluators/src/main/kotlin/Evaluator.kt",
        ])
        self.assertEqual(scope.profile, "scoped")
        self.assertEqual(scope.modules, ("evaluation:evaluators", "observability:health-engine"))

    def test_mixed_documentation_and_runtime_code_is_strong(self) -> None:
        scope = classify_paths(["README.md", "core/runtime-core/src/main/kotlin/Runtime.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("core:runtime-core",))

    def test_core_contract_change_is_strong_and_runs_all_android_modules(self) -> None:
        scope = classify_paths(["core/contracts/src/main/kotlin/Request.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("all",))

    def test_evaluation_contract_change_is_strong(self) -> None:
        scope = classify_paths(["evaluation/contracts/src/main/kotlin/EvaluationRunConfig.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("all",))

    def test_observability_contract_change_is_strong(self) -> None:
        scope = classify_paths(["observability/contracts/src/main/kotlin/Telemetry.kt"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("all",))

    def test_native_change_is_strong(self) -> None:
        scope = classify_paths(["backends/llama-cpp/src/main/cpp/runtime.cpp"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.native)
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("backends:llama-cpp",))

    def test_windows_native_path_is_normalized(self) -> None:
        scope = classify_paths([r"backends\llama-cpp\src\main\cpp\runtime.cpp"])
        self.assertEqual(scope.profile, "strong")
        self.assertTrue(scope.native)

    def test_submodule_pin_change_is_strong(self) -> None:
        scope = classify_paths(["third_party/llama.cpp"])
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("backends:llama-cpp",))

    def test_global_build_configuration_change_is_full(self) -> None:
        scope = classify_paths(["gradle/libs.versions.toml"])
        self.assertEqual(scope.profile, "full")
        self.assertEqual(scope.modules, ("all",))

    def test_unknown_implementation_path_fails_safe_full(self) -> None:
        scope = classify_paths(["new-module/src/main/kotlin/NewFeature.kt"])
        self.assertEqual(scope.profile, "full")
        self.assertEqual(scope.modules, ("all",))

    def test_validation_workflow_change_forces_full(self) -> None:
        scope = classify_paths([".github/workflows/validate.yml"])
        self.assertEqual(scope.profile, "full")
        self.assertTrue(scope.android)
        self.assertTrue(scope.native)
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("all",))

    def test_package_workflow_change_forces_full(self) -> None:
        scope = classify_paths([".github/workflows/package.yml"])
        self.assertEqual(scope.profile, "full")
        self.assertEqual(scope.modules, ("all",))

    def test_explicit_strong_can_escalate_scoped(self) -> None:
        auto = classify_paths(["models/model-download/src/main/kotlin/Download.kt"])
        scope = apply_requested_profile(auto, "strong")
        self.assertEqual(scope.profile, "strong")
        self.assertEqual(scope.modules, ("models:model-download",))

    def test_explicit_strong_does_not_downgrade_full(self) -> None:
        auto = classify_paths(["settings.gradle.kts"])
        scope = apply_requested_profile(auto, "strong")
        self.assertEqual(scope.profile, "full")

    def test_explicit_full_forces_all(self) -> None:
        auto = classify_paths(["models/model-download/src/main/kotlin/Download.kt"])
        scope = apply_requested_profile(auto, "full")
        self.assertEqual(scope.profile, "full")
        self.assertEqual(scope.modules, ("all",))

    def test_push_delegates_packaging_for_non_full_profile(self) -> None:
        scope = adjust_for_event(classify_paths(["apps/local-llm-phone-test/src/main/kotlin/MainActivity.kt"]), "push")
        self.assertEqual(scope.profile, "scoped")
        self.assertFalse(scope.packaging)
        self.assertEqual(scope.modules, ("apps:local-llm-phone-test",))

    def test_empty_diff_fails_safe_full(self) -> None:
        scope = classify_paths([])
        self.assertEqual(scope.profile, "full")
        self.assertTrue(scope.android)
        self.assertTrue(scope.native)
        self.assertTrue(scope.packaging)
        self.assertEqual(scope.modules, ("all",))


if __name__ == "__main__":
    unittest.main()
