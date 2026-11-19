# SIMP — Simplicity-first Harnex runtime

Status: active
Document type: workstream
Owner: runtime-core / llama-cpp backend (coordination; no duplicate implementation owners)
Canonical scope: workstream.simplicity-first-runtime
Read when: choosing the next evidence-backed simplification of the Harnex runtime
Last reviewed: 2026-11-19
Source checkpoint: `dev@b5def37ade90d0bb208f2acc540a6540efb48761`

## Product intent and decision

- **Depth:** PRODUCT_FEATURE for SDK ease of use; PRODUCT_NONE for behavior-preserving runtime cleanup. Embedded SDK distribution/model-support expansion would be PRODUCT_STRATEGIC.
- **Consumer/job:** Android developers need safe, reliable local LLM inference without managing JNI, model residency, sessions, Binder and recovery. Maintainers need one clear owner per resource/transition.
- **Desired outcome:** less domain-neutral integration boilerplate and fewer competing/duplicate runtime paths, without sacrificing functionality. Measure decision points, contracts and error paths—not LOC or module count alone.
- **Decision:** NARROW_SCOPE. Preserve existing one-decode, bounded-model, fail-closed Host design. Improve the existing Consumer journey; extract only proven duplicate logic; qualify memory and upstream separately.
- **Non-goals:** full rewrite, new agent/RAG framework, silent retry/cloud fallback, arbitrary GGUF promises, more resident models, GPU, generic module consolidation or extra state machines.
- **Risk/assumptions:** VALUE medium (visible boilerplate, independent-user value unmeasured); USABILITY high (connection vs authorized readiness); FEASIBILITY high (JNI, Android memory and concurrency); VIABILITY medium (SDK ABI/RedactGuard/release identity). Test whether two consumers truly duplicate stable semantics *before* adding a convenience API. JNI Base64 overhead is unmeasured.
- **Success:** existing Host/Consumer authorization and generation semantics, cancellation/terminal-once, bounded resources, privacy-safe diagnostics, exact backend/model identity and direct consumer compatibility remain intact. Observe independent integrator time-to-first-inference/repair burden with consent, not content telemetry.

## Prioritized risks and canonical owners

| ID | Priority | Source observation | Smallest sufficient response |
| --- | --- | --- | --- |
| C1 | P0 | Hello Harnex coordinates assignment/preset/activate/prepare/session/generate/close; 489-line gateway. RedactGuard owns a 607-line runtime and 310-line control-plane coordinator; not all logic is reusable. | **Existing CIH** `docs/workstreams/consumer-integration-resilience.md`: clarify setup/recovery; integrate generic-text PR #577; inspect duplication before any new public API. |
| C2 | P0 | Memory admission/context planning exists, but reviewed production `MEASURED` profiles remain absent. | **Existing MEM/Q35**: physical exact-model/device qualification. Do not create a new memory governor or claim emulator evidence proves safety. |
| C3 | P1 | `RuntimeOrchestrator.kt` has 1,469 lines and duplicated normal/guard completion branches. | **SIMP-10/20 DONE**: normal and guard completion share one terminal projection, with lifecycle owners unchanged. |
| C4 | P1/P2 | `llama_jni.cpp` 1,353 lines; positional responses and Base64 streaming. | **SIMP-30**: profile cost/maintainability before changing JNI wire protocol. |
| C5 | P2 | Production `llama.cpp` pin `b9637`/`aedb2a5e…`; v0.3 candidate PR #490 remains unmerged. | **Existing LLUP/LLRT** only: evaluate v1 in isolation; no pin upgrade without Android and physical qualification. |
| C6 | Separate strategic question | Public Maven Consumer SDK alpha.11 uses shared Binder Host; in-process runtime exists but no standalone external embedded distribution claim. | Consider an embedded packaging spike only after explicit product demand/constraints. |
| C7 | No action by default | 35 Gradle modules; high count is not evidence of harmful ownership. | Keep until measurable navigation/build/dependency cost warrants consolidation. |

## SIMP-00 findings (read-only; no executable PASS claim)

- `RuntimeOrchestrator` owns physical model/context transitions; `SessionLifecycle`, `ModelResidencyLifecycle` and `SingleDecodeScheduler` already own separate session reservations, residency state and bounded queue/cancel. **KEEP these owners.**
- `executeGeneration` repeats `completeGeneration` for normal completion vs guard stop, differing only in stop metrics. Existing `RuntimeOrchestratorTest` covers streaming, cancellation/races and successful guard stop; PR #584 consolidated the duplicate call and added a terminal-once assertion, with STRONG tests green. **DONE**.
- `executeEvaluationBatch` previously repeated cleanup across four catches. **SIMP-25 DONE** in PR #591: one cleanup path preserves typed errors, cancel semantics, terminal-once and exactly-once release attempts.
- **SIMP-26 DONE** in PR #592: both the successful-path native batch release exception and failure-path cleanup exception retain the terminal error and leave `RuntimeState.DEGRADED` after worker completion. A per-operation release-failure flag avoids a second state owner. This is truthful health reporting, not native recovery or a permanent degradation policy.
- `MemoryAdmissionControllerTest`, `MemoryAwareContextPlannerTest`, `RuntimeMemoryAdmissionIntegrationTest` and residency/session integration tests cover deterministic policy, not representative RAM/thermal qualification. **KEEP existing policy; measure on devices.**
- JNI Base64 avoids treating arbitrary UTF-8 token fragments as safe modified-UTF-8 Java strings; using byte arrays would add native buffer handling. Positional `Array<String>` is maintenance friction but not a proven bottleneck. **KEEP pending measurement; no speculative rewrite.**
- Upstream `llama.cpp v1.0.0` (2026-11-13) has exact source commit `b1b770d90d94cad9e9ea5b993bdbc383a522fb28` (annotated tag `a6d1a74dbebce3a16f4f2ceddbf38c21a08d8674`). Some core native API calls remain available but deprecated public APIs were removed. This is **not** CMake/JNI/build/GGUF certification; LLUP owns that decision.

## Work graph — independent writes, early convergence

| ID | State | Work / owner | Exit evidence |
| --- | --- | --- | --- |
| SIMP-00 | DONE | Source map above, risks and minimal candidate; docs only | Owner/test/consumer trace and decision without speculative abstraction |
| SIMP-10 | DONE | `core/runtime-core` test guard/cancel/terminal semantics; PR #584 | Existing focused tests + terminal-once assertion green at candidate |
| SIMP-20 | DONE | `core/runtime-core` one duplicate terminal-path refactor; PR #584, depends SIMP-10 | No public contract/state-owner drift; exact-head scoped/strong CI and direct consumers |
| SIMP-25 | DONE | Consolidate `executeEvaluationBatch` duplicated failure cleanup; PR #591 | Exact-head Android STRONG and regression tests passed; merged in `dev@dfa1675` |
| SIMP-26 | DONE | Preserve truthful `DEGRADED` on batch context release failure; PR #592 | Exact-head Android STRONG, two worker-idle regression tests, Repository validation and merge to `dev@48ea57d`; physical native qualification remains separate |
| SIMP-30 | BLOCKED | `backends/llama-cpp` JNI protocol cost investigation; independent of SIMP-20, after SIMP-00 | KEEP or one evidence-backed minimal edit with native/ARM64 packaging and device check |

- **Parallel owners:** CIH handles SDK, MEM/Q35 handles physical qualification, LLUP handles upstream pin. SIMP-20 and SIMP-30 may progress in parallel only without shared JNI/Kotlin protocol or public contract changes. PR #577 is integrated and supersedes closed #562; preserve the canonical Hello Harnex sample.
- **Convergence:** CIH consumer proof + MEM/Q35 evidence + SIMP runtime tests; LLUP and embedded SDK have separate decisions. After each accepted slice, transfer settled details to code/tests/SDK/memory/architecture owners; delete this workstream once complete.
- **Stop rule:** refuse an extraction that adds competing owners, adapters or more complexity than it removes.

## Validation and execution

- Plan/audit: PRODUCT_NONE docs, DELIVERY ITERATION → INTEGRATION, **LEAN**; repository documentation checks.
- Runtime terminal/lifecycle changes: PRODUCT_NONE if behavior-preserving, **STRONG** when resource/concurrency semantics are touched, selector may escalate. Verify normal/guard/cancel/queue/context/session, Consumer compatibility, code formatting and exact candidate/base.
- Native/JNI/upstream changes: **STRONG/FULL** depending on selector; Kotlin/host-native, ARM64 package/R8 and affected GGUF/quality probes. Pin changes invalidate measured performance evidence.
- Public SDK modifications: ABI + Maven external fixture, Hello Harnex and RedactGuard compatibility and Binder/two-APK E2E.
- Missing deterministic Android tooling is **REMOTE_AUTOMATED**; representative physical GGUF/RAM/thermal/signer evidence is **REAL_ENVIRONMENT** and separately blocking when applicable. A PR or a green unrelated run is not proof of release readiness.

## Resume checkpoint

- Base at audit: `dev@b5def37`; SIMP-25 and SIMP-26 are integrated through `dev@48ea57d`. Refresh live base, PRs and selectors before implementation/merge.
- PR #584 integrated the terminal-once simplification into `dev` as `dfaccd3` after STRONG automated validation; PR #577 integrated the generic-text onboarding as `52b9cf4`. PR #578 Binder invalidation was integrated in `dev` as `c2ab02d` after STRONG Android and external Consumer validation.
- Production pin remains `b9637`; v1.0.0 is an unqualified candidate. No generic import/embedded SDK/public convenience API has been implemented by SIMP.
