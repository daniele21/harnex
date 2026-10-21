#!/usr/bin/env python3
import argparse
import json
import statistics
from pathlib import Path

IDENTITY_KEYS = (
    "deviceModel",
    "androidRelease",
    "sdkInt",
    "abi",
    "model08bDigest",
    "model2bDigest",
    "thermalStartMax",
    "profile",
)
METRICS = (
    "ttftMs",
    "prefillMs",
    "decodeMs",
    "totalMs",
    "prefillTokensPerSecond",
    "decodeTokensPerSecond",
    "processPssKb",
    "availableMemoryBytes",
    "thermalStatus",
)


def parse_args():
    parser = argparse.ArgumentParser(description="Compare paired LLUP-50 physical evidence without applying promotion thresholds.")
    parser.add_argument("--control", required=True, type=Path, help="Control side evidence directory containing manifest.json")
    parser.add_argument("--candidate", required=True, type=Path, help="Candidate side evidence directory containing manifest.json")
    parser.add_argument("--output-dir", required=True, type=Path)
    return parser.parse_args()


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path):
    records = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            records.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise SystemExit(f"Invalid JSONL {path}:{line_number}: {exc}") from exc
    return records


def median(records, metric):
    values = [record.get(metric) for record in records]
    values = [value for value in values if isinstance(value, (int, float))]
    return statistics.median(values) if values else None


def delta(control, candidate):
    if control is None or candidate is None:
        return {"absolute": None, "percent": None}
    absolute = candidate - control
    percent = None if control == 0 else absolute * 100.0 / control
    return {"absolute": absolute, "percent": percent}


def group_load(records):
    result = {}
    for tier in ("B0_8", "B2"):
        tier_records = [record for record in records if record.get("modelTier") == tier]
        if len(tier_records) < 3:
            raise SystemExit(f"Expected at least three model-load records for {tier}, got {len(tier_records)}")
        result[tier] = {
            "samples": len(tier_records),
            "loadDurationMsMedian": median(tier_records, "loadDurationMs"),
            "thermalStatusBeforeMax": max(record.get("thermalStatusBefore", -1) for record in tier_records),
            "thermalStatusAfterMax": max(record.get("thermalStatusAfter", -1) for record in tier_records),
        }
    return result


def group_tuning(records):
    result = {}
    for tier in ("B0_8", "B2"):
        tier_records = [record for record in records if record.get("modelTier") == tier]
        if not tier_records:
            raise SystemExit(f"Missing tuning records for {tier}")
        result[tier] = {}
        for load_kind in ("COLD", "WARM"):
            subset = [record for record in tier_records if record.get("modelLoadKind") == load_kind]
            if load_kind == "COLD" and len(subset) != 1:
                raise SystemExit(f"Expected exactly one COLD tuning record for {tier}, got {len(subset)}")
            if load_kind == "WARM" and len(subset) < 3:
                raise SystemExit(f"Expected at least three WARM tuning records for {tier}, got {len(subset)}")
            result[tier][load_kind.lower()] = {
                "samples": len(subset),
                **{metric: median(subset, metric) for metric in METRICS},
            }
    return result


def compare_metric_sets(control, candidate, metrics):
    return {
        metric: {
            "control": control.get(metric),
            "candidate": candidate.get(metric),
            "delta": delta(control.get(metric), candidate.get(metric)),
        }
        for metric in metrics
    }


def build_comparison(control_dir, candidate_dir):
    control_manifest = load_json(control_dir / "manifest.json")
    candidate_manifest = load_json(candidate_dir / "manifest.json")
    if control_manifest.get("label") != "control" or candidate_manifest.get("label") != "candidate":
        raise SystemExit("Expected control/candidate manifests with matching labels")
    mismatches = {
        key: {"control": control_manifest.get(key), "candidate": candidate_manifest.get(key)}
        for key in IDENTITY_KEYS
        if control_manifest.get(key) != candidate_manifest.get(key)
    }
    if mismatches:
        raise SystemExit(f"LLUP-50 A/B identity mismatch: {json.dumps(mismatches, sort_keys=True)}")

    control_load = group_load(load_jsonl(control_dir / "model-load-evidence.jsonl"))
    candidate_load = group_load(load_jsonl(candidate_dir / "model-load-evidence.jsonl"))
    control_tuning = group_tuning(load_jsonl(control_dir / "tuning-evidence.jsonl"))
    candidate_tuning = group_tuning(load_jsonl(candidate_dir / "tuning-evidence.jsonl"))

    tiers = {}
    for tier in ("B0_8", "B2"):
        tiers[tier] = {
            "modelLoad": compare_metric_sets(
                control_load[tier], candidate_load[tier],
                ("loadDurationMsMedian", "thermalStatusBeforeMax", "thermalStatusAfterMax"),
            ),
            "cold": compare_metric_sets(control_tuning[tier]["cold"], candidate_tuning[tier]["cold"], METRICS),
            "warm": compare_metric_sets(control_tuning[tier]["warm"], candidate_tuning[tier]["warm"], METRICS),
        }

    return {
        "schemaVersion": 1,
        "evidenceType": "LLUP50_PHYSICAL_AB_COMPARISON",
        "control": {
            "evidenceSourceCommit": control_manifest["evidenceSourceCommit"],
            "runtimeSourceCommit": control_manifest["runtimeSourceCommit"],
            "backendRevision": control_manifest["backendRevision"],
        },
        "candidate": {
            "evidenceSourceCommit": candidate_manifest["evidenceSourceCommit"],
            "runtimeSourceCommit": candidate_manifest["runtimeSourceCommit"],
            "backendRevision": candidate_manifest["backendRevision"],
        },
        "identity": {key: control_manifest[key] for key in IDENTITY_KEYS},
        "tiers": tiers,
        "lifecycleEvidence": {
            "controlSha256": control_manifest["evidenceFiles"]["lifecycle"],
            "candidateSha256": candidate_manifest["evidenceFiles"]["lifecycle"],
        },
        "promotionDecision": "UNSET",
        "thresholdPolicyApplied": False,
    }


def markdown(comparison):
    lines = [
        "# LLUP-50 physical A/B comparison",
        "",
        f"- control runtime: `{comparison['control']['runtimeSourceCommit']}` / `{comparison['control']['backendRevision']}`",
        f"- candidate runtime: `{comparison['candidate']['runtimeSourceCommit']}` / `{comparison['candidate']['backendRevision']}`",
        f"- device: `{comparison['identity']['deviceModel']}` Android {comparison['identity']['androidRelease']} (SDK {comparison['identity']['sdkInt']})",
        "- thresholds: **not applied**; this report is descriptive evidence only",
        "",
    ]
    for tier, groups in comparison["tiers"].items():
        lines.extend([f"## {tier}", "", "| Group | Metric | Control | Candidate | Delta | Delta % |", "| --- | --- | ---: | ---: | ---: | ---: |"])
        for group_name, group in groups.items():
            for metric, item in group.items():
                control = item["control"]
                candidate = item["candidate"]
                absolute = item["delta"]["absolute"]
                percent = item["delta"]["percent"]

                def fmt(value):
                    if value is None:
                        return "n/a"
                    if isinstance(value, float):
                        return f"{value:.3f}"
                    return str(value)

                lines.append(f"| {group_name} | {metric} | {fmt(control)} | {fmt(candidate)} | {fmt(absolute)} | {fmt(percent)} |")
        lines.append("")
    return "\n".join(lines) + "\n"


def main():
    args = parse_args()
    comparison = build_comparison(args.control, args.candidate)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    json_path = args.output_dir / "llup50-comparison.json"
    md_path = args.output_dir / "llup50-comparison.md"
    json_path.write_text(json.dumps(comparison, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    md_path.write_text(markdown(comparison), encoding="utf-8")
    print(json_path)
    print(md_path)


if __name__ == "__main__":
    main()
