#!/usr/bin/env python3
"""Deterministic fixtures for MEM-B physical candidate evidence acceptance."""

import copy
import runpy
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("summarize-qwen35-memory-calibration.py")
MODULE = runpy.run_path(str(SCRIPT), run_name="harnex_calibration_test")
summarize = MODULE["summarize"]
CURATED = MODULE["CURATED"]
HEAD = "a" * 64
REVISION = "aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3"


def phase(name, before, after, peak):
    return {
        "phase": name,
        "samples": 1 if name in ("cold_baseline", "settled_after_unload") else 2,
        "elapsedMs": 10,
        "pssBeforeKb": before,
        "pssAfterKb": after,
        "pssPeakObservedKb": peak,
        "nativeHeapBeforeBytes": 100,
        "nativeHeapAfterBytes": 200,
        "javaHeapBeforeBytes": 300,
        "javaHeapAfterBytes": 400,
        "availableBeforeBytes": 8_000_000_000,
        "minimumAvailableBytes": 7_000_000_000,
        "availableAfterBytes": 7_900_000_000,
        "lowMemoryBefore": False,
        "lowMemoryAfter": False,
        "thermalBefore": 0,
        "thermalAfter": 0,
    }


def sample(tier, context):
    digest, profile, cpu_threads = CURATED[tier]
    return {
        "schemaVersion": 1,
        "status": "CANDIDATE_UNREVIEWED",
        "harnessCommit": HEAD,
        "backendId": "llama.cpp",
        "backendRevision": REVISION,
        "modelDigest": digest,
        "tier": tier,
        "runtimeProfileId": profile,
        "runtimeProfileVersion": 1,
        "deviceModel": "Physical test device",
        "deviceManufacturer": "Hardware vendor",
        "deviceBuildFingerprint": "device/verified/build",
        "sdkInt": 36,
        "abi": "arm64-v8a",
        "contextTokens": context,
        "cpuThreads": cpu_threads,
        "batchThreads": 4,
        "batchSize": 128,
        "microBatchSize": 64,
        "gpuLayers": 0,
        "useMmap": True,
        "useMlock": False,
        "flashAttention": False,
        "kvCacheTypeK": "default",
        "kvCacheTypeV": "default",
        "outputTokens": 16,
        "workload": "fixed-native-probe-v1",
        "sampleIntervalMs": 200,
        "phases": [
            phase("cold_baseline", 800, 800, 800),
            phase("backend_initialize", 800, 810, 815),
            phase("model_load", 810, 1300, 1350),
            phase("context_create", 1300, 1450, 1480),
            phase("generation", 1450, 1470, 1530),
            phase("context_release", 1470, 1320, 1470),
            phase("model_unload", 1320, 830, 1320),
            phase("backend_shutdown", 830, 820, 830),
            phase("settled_after_unload", 825, 825, 825),
        ],
    }


def complete():
    return [sample(tier, context) for tier in CURATED for context in (1024, 2048) for _ in range(3)]


class PhysicalCostEvidenceTest(unittest.TestCase):
    def test_complete_run_exports_candidate_only_and_includes_generation_peak(self):
        result = summarize(complete(), HEAD, REVISION, 3, [1024, 2048])
        self.assertEqual(result["status"], "CANDIDATE_UNREVIEWED")
        self.assertFalse(result["automaticProfilePromotion"])
        self.assertFalse(result["safetyMarginApplied"])
        self.assertTrue(result["samplingPeakIsLowerBound"])
        self.assertEqual(len(result["cases"]), 4)
        first = result["cases"][0]
        self.assertEqual(first["coldRepetitions"], 3)
        self.assertEqual(first["maximumObservedModelResidentBytes"], (1300 - 800) * 1024)
        self.assertEqual(first["maximumObservedModelPeakIncrementBytes"], (1350 - 800) * 1024)
        self.assertEqual(first["maximumObservedContextPeakIncrementBytes"], (1530 - 1300) * 1024)
        self.assertEqual(first["maximumObservedPostUnloadResidualDeltaKb"], 25)

    def test_partial_repetitions_cannot_silently_promote(self):
        records = complete()[:-1]
        with self.assertRaisesRegex(ValueError, "Incomplete or duplicated"):
            summarize(records, HEAD, REVISION, 3, [1024, 2048])

    def test_mismatched_artifact_and_backend_fail_closed(self):
        for key, value, message in (
            ("modelDigest", "f" * 64, "Curated model digest mismatch"),
            ("backendRevision", "f" * 64, "Backend mismatch"),
            ("runtimeProfileId", "other", "Runtime profile mismatch"),
            ("flashAttention", True, "Attention/KV tuning mismatch"),
        ):
            with self.subTest(key=key):
                records = complete()
                records[0][key] = value
                with self.assertRaisesRegex(ValueError, message):
                    summarize(records, HEAD, REVISION, 3, [1024, 2048])

    def test_device_or_phase_incompatibility_fails_closed(self):
        for mutation, message in (
            (lambda r: r.update(deviceBuildFingerprint="different"), "Device identity differs"),
            (lambda r: r["phases"].pop(), "Incomplete phase windows"),
            (lambda r: r["phases"][2].update(pssPeakObservedKb=500), "PSS peak does not contain"),
            (lambda r: r["phases"][2].update(minimumAvailableBytes=None), "Invalid or absent"),
            (lambda r: r["phases"][2].update(lowMemoryAfter=True), "Platform low-memory occurred"),
            (lambda r: r.update(status="MEASURED"), "must not claim MEASURED"),
        ):
            with self.subTest(message=message):
                records = copy.deepcopy(complete())
                mutation(records[0] if "device" not in message else records[1])
                with self.assertRaisesRegex(ValueError, message):
                    summarize(records, HEAD, REVISION, 3, [1024, 2048])

    def test_unknown_or_duplicated_context_is_not_accepted(self):
        for contexts in ([1024, 4096], [1024, 1024], [1024, 1234]):
            with self.subTest(contexts=contexts), self.assertRaises(ValueError):
                summarize(complete(), HEAD, REVISION, 3, contexts)


if __name__ == "__main__":
    unittest.main()
