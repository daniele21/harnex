# Memory-management current state

Status: active
Document type: workstream-state
Owner: runtime-memory
Canonical scope: memory-management.current-state
Read when: checking what memory-management behavior is integrated versus still evidence-gated
Last reviewed: 2026-08-16

The repository-wide status owner remains [`../current-state.md`](../current-state.md). This page narrows that state to memory governance and must not be used to claim physical-device readiness that has not been measured.

## Current baseline

The runtime now has explicit ownership and bounds rather than relying on single-decode serialization as a proxy for memory safety:

- opaque backend handles and native RAII own model/context release mechanics;
- `SessionLifecycle` is authoritative for request admission, close intent, drain and release reservation;
- runtime shutdown converges active cancellation into context release, model unload and backend shutdown;
- one loaded model remains the default runtime invariant;
- `SingleDecodeScheduler` bounds active + queued work, with capacity recovered on completion/cancellation and a close-race regression covered;
- the Android service host already bounds connections, sessions and per-consumer outstanding requests;
- Android low-memory/trim callbacks and resource snapshots remain the platform pressure/measurement inputs.

## Integrated memory governor

`core/runtime-core` contains one backend-neutral admission model:

- `RuntimeMemoryObservation` preserves nullable PSS, native heap, Java heap, available-memory and low-memory signals;
- `RuntimeMemoryBudget` owns available-memory floor, safety reserve, optional PSS ceiling and resident-context limit;
- `MemoryAdmissionController` makes overflow-safe typed decisions for `MODEL` and `CONTEXT` resources;
- `MemoryAwareContextPlanner` evaluates approved context tiers without going below prompt/output capacity;
- `MemoryAwareModelLoadPlanner` gates cold/switch model loads;
- both model and context allocation paths run admission before the corresponding native allocation;
- context rejection reaches the public typed `MEMORY_BUDGET_EXCEEDED` configuration error;
- model-load rejection occurs before backend initialization/native load and remains a typed internal admission failure at the prepare boundary.

A reusable context or already-loaded compatible model is not re-admitted because no new allocation is occurring.

## Cost evidence

Context and model-load cost registries are identity-bound to exact model profile/digest and backend ID/revision, and preserve provenance:

- `THEORETICAL` — static/conservative input;
- `CANDIDATE` — calibrated but not approved physical evidence;
- `MEASURED` — reviewed representative-device evidence for compatible composition.

No numeric production `MEASURED` profiles are committed yet. Qwen3.5 recurrent/linear-attention state is therefore not approximated as a pure KV-cache formula for certification.

## Android composition and residency

The phone-test composition adapts the canonical Android `ResourceSnapshotProvider` into `RuntimeMemoryObservation` without reversing module dependencies.

### MEM-A — shadow admission (integration, not calibration)

The primary `HarnessRuntimeGraph` now wires **all three existing planners** (cold model, ordinary context, aggregate evaluation batch) using a fresh Android resource snapshot at each new allocation boundary. Snapshots are retained through the existing bounded telemetry repository, without prompt/output content. The testable `RuntimeOrchestrator` option `enforceMemoryAdmission = false` records `memory.admission.shadow` decisions but never rejects, downshifts, or claims an allocation was approved. The previous default remains enforcing when a caller explicitly supplies planners.

**No production cost profiles are supplied in this shadow composition.** Missing estimates are recorded as `MEMORY_COST_ESTIMATE_UNAVAILABLE`; the shadow-only controller has zero numeric budget floors and **must not be reused for enforcement**. Shadow telemetry is evidence of attempted assessment, not certified headroom and not a `MEASURED` cost profile. Snapshot capture or persistence failure must not stop inference in observation mode. Existing Android low-memory callbacks, one-model residency, warm-idle TTL and bounded work remain authoritative.

Before any enforcing Android policy, publish reviewed model-load/context peak-cost profiles that distinguish exact model artifact, backend revision, runtime tuning identity and device class as applicable (including batch/ubatch, flash attention and other material memory knobs). Calibrate safety floors/reserve, ensure the profile registry rejects incompatible identities, then enable real admission only for qualified combinations. Do not silently enforce with the shadow placeholders.

Warm shared-runtime residency is bounded by policy:

- Binder disconnect is only a demand-absent signal;
- reconnect/rebind cancels pending release;
- TTL expiry delegates to `unloadIdleModel()` and reschedules when the runtime is still busy;
- critical platform pressure overrides normal TTL behavior;
- service destruction does not redefine process-scoped runtime ownership.

The current 60-second phone-test TTL is a labelled candidate, not a device-calibrated claim.

## Observability and regression

The observability path now includes:

- a bounded `MemoryWindowRecorder` over canonical `ResourceSnapshot` samples;
- PSS baseline/peak/residual and minimum-available-memory summarization;
- independent PASS/WARN/FAIL memory regression evaluation;
- typed `memory.admission` structured logs for governor allow/downshift/reject decisions, without prompt or generated-content fields.

The regression evaluator currently thresholds PSS peak/residual and available-memory floor. Native-heap and thermal signals remain available raw evidence and may be added to policy only when their interpretation is defined.

## Remaining gap: representative-device evidence

No repository/JVM/emulator test can close the remaining milestones. MEM-7 and MEM-8 require controlled physical runs that capture at least:

1. cold baseline;
2. model-load peak and warm residency;
3. context-create peaks for approved tiers;
4. generation peak/minimum available memory;
5. post-context-close and post-model-unload recovery;
6. repeated-cycle residual trend;
7. cancellation and critical-pressure cleanup;
8. warm-idle disconnect/reconnect behavior;
9. model switch/reload and overload recovery;
10. thermal state for the controlled run identity.

Only evidence that preserves exact artifact/backend/harness/device/runtime identity may promote a cost record to `MEASURED` or support a safe-tier/readiness claim.

## Next gate

Software milestones MEM-0 through MEM-6 are complete. MEM-7 is partial because the capture/admission/profile infrastructure exists but physical calibration has not been executed. MEM-8 remains planned until that evidence plus lifecycle/soak scenarios pass the certification matrix.
