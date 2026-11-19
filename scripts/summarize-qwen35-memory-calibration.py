#!/usr/bin/env python3
"""Validate exact-identity physical memory windows; export UNREVIEWED candidate envelopes only."""

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

CURATED = {
    "0.8b": ("bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", "qwen35-08b-android-candidate", 2),
    "2b": ("aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", "qwen35-2b-android-candidate", 4),
}
PHASES = (
    "cold_baseline",
    "backend_initialize",
    "model_load",
    "context_create",
    "generation",
    "context_release",
    "model_unload",
    "backend_shutdown",
    "settled_after_unload",
)
APPROVED_CONTEXTS = {1024, 2048, 4096, 8192}
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def require(condition, detail):
    if not condition:
        raise ValueError(detail)


def integer(obj, key):
    value = obj.get(key)
    require(type(value) is int and value >= 0, "Invalid or absent non-negative integer: " + key)
    return value


def validate_sample(record, head, revision, device_identity):
    require(isinstance(record, dict), "Evidence must be an object")
    require(record.get("schemaVersion") == 1, "Unsupported evidence schema")
    require(record.get("status") == "CANDIDATE_UNREVIEWED", "Evidence must not claim MEASURED")
    require(record.get("harnessCommit") == head, "Harness commit mismatch")
    require(record.get("backendId") == "llama.cpp" and record.get("backendRevision") == revision, "Backend mismatch")
    tier = record.get("tier")
    require(tier in CURATED, "Unsupported model tier")
    digest, profile, threads = CURATED[tier]
    require(record.get("modelDigest") == digest, "Curated model digest mismatch")
    require(record.get("runtimeProfileId") == profile and record.get("runtimeProfileVersion") == 1, "Runtime profile mismatch")
    require(record.get("cpuThreads") == threads and record.get("batchThreads") == 4, "CPU tuning mismatch")
    require(record.get("batchSize") == 128 and record.get("microBatchSize") == 64, "Batch tuning mismatch")
    require(record.get("gpuLayers") == 0 and record.get("useMmap") is True and record.get("useMlock") is False, "Model load tuning mismatch")
    require(record.get("flashAttention") is False and record.get("kvCacheTypeK") == "default" and record.get("kvCacheTypeV") == "default", "Attention/KV tuning mismatch")
    require(record.get("workload") == "fixed-native-probe-v1" and record.get("outputTokens") == 16, "Workload mismatch")
    require(record.get("sampleIntervalMs") == 200 and record.get("abi") == "arm64-v8a", "Sample/ABI mismatch")
    context = integer(record, "contextTokens")
    require(context in APPROVED_CONTEXTS, "Unapproved context tier")
    identity = (record.get("deviceModel"), record.get("deviceManufacturer"), record.get("deviceBuildFingerprint"), record.get("sdkInt"))
    require(all(identity[:3]) and type(identity[3]) is int and identity[3] >= 26, "Incomplete physical device identity")
    if device_identity is not None:
        require(identity == device_identity, "Device identity differs within calibration wave")
    raw_phases = record.get("phases")
    require(isinstance(raw_phases, list) and len(raw_phases) == len(PHASES), "Incomplete phase windows")
    require([p.get("phase") for p in raw_phases if isinstance(p, dict)] == list(PHASES), "Unexpected phase order")
    phases = {p["phase"]: p for p in raw_phases}
    for name, phase in phases.items():
        before = integer(phase, "pssBeforeKb")
        after = integer(phase, "pssAfterKb")
        peak = integer(phase, "pssPeakObservedKb")
        require(peak >= max(before, after), "PSS peak does not contain boundary samples: " + name)
        require(integer(phase, "samples") >= (1 if name in (PHASES[0], PHASES[-1]) else 2), "Insufficient samples: " + name)
        integer(phase, "elapsedMs")
        integer(phase, "nativeHeapBeforeBytes")
        integer(phase, "nativeHeapAfterBytes")
        integer(phase, "javaHeapBeforeBytes")
        integer(phase, "javaHeapAfterBytes")
        for field in ("availableBeforeBytes", "minimumAvailableBytes", "availableAfterBytes"):
            integer(phase, field)
        require(phase.get("lowMemoryBefore") is False and phase.get("lowMemoryAfter") is False, "Platform low-memory occurred")
        for field in ("thermalBefore", "thermalAfter"):
            require(type(phase.get(field)) is int and 0 <= phase[field] <= 6, "Missing/invalid thermal status")
    require(phases["cold_baseline"]["pssAfterKb"] == phases["cold_baseline"]["pssBeforeKb"], "Invalid cold baseline")
    return tier, context, identity, phases


def summarize(records, head, revision, repeats, contexts):
    require(SHA256.fullmatch(head) and SHA256.fullmatch(revision), "Invalid commit/backend identity")
    require(repeats >= 3, "At least three independent cold repetitions are required")
    require(contexts and set(contexts).issubset(APPROVED_CONTEXTS) and len(contexts) == len(set(contexts)), "Invalid approved context set")
    expected = {(tier, context) for tier in CURATED for context in contexts}
    grouped = defaultdict(list)
    device = None
    for record in records:
        tier, context, identity, phases = validate_sample(record, head, revision, device)
        if device is None:
            device = identity
        require((tier, context) in expected, "Unexpected tier/context case")
        baseline = phases["cold_baseline"]["pssAfterKb"]
        model_load = phases["model_load"]
        context_create = phases["context_create"]
        generation = phases["generation"]
        peak_model = max(phases["backend_initialize"]["pssPeakObservedKb"], model_load["pssPeakObservedKb"])
        peak_context = max(context_create["pssPeakObservedKb"], generation["pssPeakObservedKb"])
        grouped[tier, context].append({
            "modelResidentObservedBytes": max(0, model_load["pssAfterKb"] - baseline) * 1024,
            "modelPeakObservedIncrementBytes": max(0, peak_model - baseline) * 1024,
            "contextResidentObservedBytes": max(0, context_create["pssAfterKb"] - context_create["pssBeforeKb"]) * 1024,
            "contextPeakObservedIncrementBytes": max(0, peak_context - context_create["pssBeforeKb"]) * 1024,
            "postUnloadResidualDeltaKb": phases["settled_after_unload"]["pssAfterKb"] - baseline,
            "minimumAvailableBytes": min(p["minimumAvailableBytes"] for p in phases.values()),
            "maximumThermalStatus": max(p["thermalAfter"] for p in phases.values()),
        })
    require(set(grouped) == expected, "Missing tier/context case")
    require(all(len(samples) == repeats for samples in grouped.values()), "Incomplete or duplicated cold repetitions")

    cases = []
    for tier, context in sorted(expected):
        samples = grouped[tier, context]
        fields = {key: [s[key] for s in samples] for key in samples[0]}
        cases.append({
            "tier": tier,
            "modelDigest": CURATED[tier][0],
            "runtimeProfileId": CURATED[tier][1],
            "contextTokens": context,
            "coldRepetitions": repeats,
            "maximumObservedModelResidentBytes": max(fields["modelResidentObservedBytes"]),
            "maximumObservedModelPeakIncrementBytes": max(fields["modelPeakObservedIncrementBytes"]),
            "maximumObservedContextResidentBytes": max(fields["contextResidentObservedBytes"]),
            "maximumObservedContextPeakIncrementBytes": max(fields["contextPeakObservedIncrementBytes"]),
            "maximumObservedPostUnloadResidualDeltaKb": max(fields["postUnloadResidualDeltaKb"]),
            "minimumObservedAvailableBytes": min(fields["minimumAvailableBytes"]),
            "maximumObservedThermalStatus": max(fields["maximumThermalStatus"]),
        })

    return {
        "schemaVersion": 1,
        "status": "CANDIDATE_UNREVIEWED",
        "automaticProfilePromotion": False,
        "safetyMarginApplied": False,
        "samplingPeakIsLowerBound": True,
        "measurement": "observed_process_pss_not_native_allocation_ownership",
        "scope": "native_backend_phases_not_end_to_end_runtime_admission",
        "harnessCommit": head,
        "backendRevision": revision,
        "device": {
            "model": device[0],
            "manufacturer": device[1],
            "buildFingerprint": device[2],
            "sdkInt": device[3],
            "abi": "arm64-v8a",
        },
        "cases": cases,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("raw", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--harness-commit", required=True)
    parser.add_argument("--backend-revision", required=True)
    parser.add_argument("--repeats", required=True, type=int)
    parser.add_argument("--contexts", required=True)
    args = parser.parse_args()
    contexts = [int(value) for value in args.contexts.split(",")]
    records = [json.loads(line) for line in args.raw.read_text().splitlines() if line.strip()]
    summary = summarize(records, args.harness_commit, args.backend_revision, args.repeats, contexts)
    args.output.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print("Validated unreviewed candidate cost envelope for", len(summary["cases"]), "tier/context cases")


if __name__ == "__main__":
    main()
