# SIMP — Simplicity-first Harnex runtime review

Status: active
Document type: workstream
Owner: runtime-core / llama-cpp backend (cross-lane coordination only)
Canonical scope: workstream.simplicity-first-runtime
Read when: prioritizing removal of incidental runtime complexity or deciding the next smallest Harnex simplification slice
Last reviewed: 2026-11-18
Source checkpoint: `dev@4e5ffda473c87cbb99ab6fb9f4aa75f4e402a477`

## Intent and decision

- **Product depth:** PRODUCT_FEATURE for making the existing Consumer SDK easier to use; PRODUCT_NONE for strictly behavior-preserving internal cleanup. Embedded distribution, arbitrary GGUF support or a changed Host trust model would be PRODUCT_STRATEGIC and require separate shaping.
- **User / job:** Android app developers need dependable on-device LLM inference without reimplementing model, memory, session, cancellation or Binder policy. Maintainers need to change the runtime without coordinating multiple owners of the same state.
- **Outcome:** fewer *consumer-owned* steps to first real inference and less *incidental internal* code/branching; no regression to correctness, resource bounds, cancellation, privacy or device compatibility.
- **Decision:** NARROW_SCOPE. Prioritize a usable generic-text Consumer journey, existing lifecycle/memory correctness, and two small, evidence-based cleanup slices. Do **not** launch a whole-repo rewrite, module consolidation or automatic llama.cpp v1 promotion.
- **Non-goals:** new agent framework, RAG, domain workflows, silent auto-retry/fallback, generic GGUF import, extra resident models, parallel production decode, speculative runtime abstractions.
- **Risks:** VALUE medium (developer friction is source-observed but not user-measured); USABILITY high (long Consumer setup lifecycle); FEASIBILITY high (native/session/memory races and JNI compatibility); VIABILITY medium (public SDK ABI, RedactGuard, package size, release evidence).
- **Material assumptions:** A1: a small high-level Consumer helper reduces *domain-neutral* duplication; check Hello Harnex and RedactGuard before adding public API. A2: extracting tightly scoped generation logic will reduce maintenance cost without competing state owners; falsify with contract map and focused tests. A3: replacing JNI `Array<String>`/Base64 lowers complexity or overhead; profile and compare total code and failure paths first.
- **Quality:** one canonical runtime/model/session authority; fail-closed Android caller authorization; no prompt/output in normal logs; consistent errors; bounded memory; terminal-once delivery; release on every exit; exact model/backend identity; same external behavior unless explicitly approved.
- **Acceptance:** directly supported Consumer journey succeeds, current tests/ABI remain compatible, no competing resource owner introduced, behavior remains equivalent and relevant device evidence retains its exact-identity meaning.
- **Outcome measure:** count domain-neutral integration steps/boilerplate in at least two consumers, number of distinct paths implementing generation/error/cleanup, changed branches/interfaces, and failures introduced. LOC and number of Gradle modules are diagnostic, **not** goals.
- **Impact/post-release question:** can a new independent Consumer reach a real authorized inference with fewer integration decisions and no new support/recovery issues? Use opt-in developer observation, not content telemetry.

## Grounded friction / risk register

| ID | Priority | Observed evidence and risk | Treatment / canonical owner |
| --- | --- | --- | --- |
| C1 | P0 | Hello Harnex coordinates assignment/preset/activation/prepare/session/generation/cleanup; `HelloHarnexClient.kt` is 489 lines. RedactGuard has a 607-line Consumer runtime and a 310-line control-plane coordinator; some complexity is domain-specific. | **Existing CIH lane** first; make generic text usable, audit repeated domain-neutral steps and only then justify a small convenience API. Do not duplicate CIH tasks here. |
| C2 | P0 | Memory-admission planners, pressure handling and lifecycle exist; `docs/memory-management/current-state.md` reports no reviewed production `MEASURED` cost profiles. Mobile admission claims remain device-dependent. | **Existing MEM/Q35/LLRT owners**: qualify existing policies on representative hardware; do not replace the governor or turn missing evidence into claimed safety. |
| C3 | P1 | `RuntimeOrchestrator.kt` is 1,469 lines; `executeGeneration` and batch handling are suppressed for complexity/length. Owner classes for session, generation and residency already exist. | **SIMP-00/10/20**: characterize, then extract only actual cohesive responsibilities while retaining one physical transition owner. File length is not a failure by itself. |
| C4 | P1/P2 | `llama_jni.cpp` is 1,353 lines; JNI uses positional `Array<String>` responses and Base64 chunk transfer. This may increase protocol maintenance cost; performance impact unproven. | **SIMP-30**, backend owner: inventory/measure first, remove needless duplication safely, avoid a wholesale JNI protocol redesign. |
| C5 | P2 | `dev`/ `main` pin `llama.cpp` `b9637` at `aedb2a5e…`; existing `v0.3.0` candidate PR #490 and LLUP qualification workstream are not integrated. Backend revision is represented in backend/profile identity. | **Existing LLUP/LLRT lane** only: compare exact candidate revisions including v1 if appropriate; preserve baseline and re-run affected behavior/packaging/quality/physical tests before any promotion. |
| C6 | P3 / decision | In-process core exists; publicly consumed Maven `consumer-android:0.1.0-alpha.11` uses shared Binder Host. An independently distributed embedded SDK is not yet established. | Separate PRODUCT_STRATEGIC discovery/spike only if consumer demand warrants it; not part of this cleanup. |
| C7 | No action by default | 34 Gradle modules and several large classes indicate possible navigation/build cost, not proven harmful module boundaries. | Consolidate only with dependency/build-cost evidence and without merging independently owned responsibilities. |

## Priority and ownership: do not create duplicate plans

1. **First user-facing win (P0):** execute existing `docs/workstreams/consumer-integration-resilience.md` (CIH-10/20, then CIH-30 if justified). Resolve open PR #577 against #562 before editing `samples/hello-harnex`; show generic-text inference, correct readiness and cleanup. Preserve public Consumer SDK and RedactGuard behavior.
2. **First mobile reliability win (P0):** existing `docs/memory-management/`, `docs/qwen35/` and `docs/llama-cpp-runtime-optimization-plan.md` own representative context/model memory and lifecycle evidence. Treat missing measurements as blockers for claims, not an excuse for a new governor.
3. **First SIMP-owned code win (P1):** isolate one well-proved coordination responsibility from `RuntimeOrchestrator` after SIMP-00, with test parity and no public API/state ownership changes. Prefer cohesive extraction over sweeping class splitting.
4. **Later native cleanup (P1/P2):** SIMP-30 only when a measured/structural problem merits the JNI change. Backend pin qualification remains entirely in LLUP, not mixed with cleanup.
5. **Defer:** direct embedded SDK publication, new model-family/product-support policy, broad module merging, GPU expansion and multi-model residency.

## SIMP work graph (unique writes only)

| ID | State | Owns / exact result | Dependency | Iteration proof |
| --- | --- | --- | --- | --- |
| SIMP-00 | READY | **Read-only** map of `RuntimeOrchestrator` request -> model/context -> generation -> terminal/cleanup; identify which branches and collaborators have truly duplicate responsibilities. Define baseline tests and non-regression matrix. | none | Evidence-backed map names owner, consumers, tests, and a smallest feasible extraction, or decides DO_NOT_BUILD. |
| SIMP-10 | BLOCKED | `core/runtime-core`: regression tests around chosen normal generation path, cancellation/close, queue errors, context release and one terminal event; no production behavior change. | SIMP-00 | Focused deterministic tests pass for baseline exact source. |
| SIMP-20 | BLOCKED | `core/runtime-core`: one minimal root-cause simplification, e.g. isolate preparation or result projection **only if** SIMP-00 finds an independently meaningful boundary. No second scheduler, session or residency authority. | SIMP-10 | Behavior tests + direct consumers pass; simpler responsibility map with no new lifecycle leaks or broad suppression. |
| SIMP-30 | BLOCKED | `backends/llama-cpp`: profile/trace JNI boundary and error/chunk conversion, then choose KEEP or one small typed/centralized conversion improvement. Do not change upstream pin in this slice. | SIMP-00 (can run after it independently of SIMP-20) | Kotlin + host-native tests, ARM64 build/package proof for actual native changes; no worsened correctness/latency/allocations. |

Work graph deliberately excludes CIH, MEM, LLUP and embedded implementation tasks: they remain in their existing canonical plans.

## Dependency and convergence

```text
Existing CIH: generic text / Consumer setup / integration proof ─────────┐
Existing MEM/Q35: physical memory and lifecycle evidence ────────────────┤
                                                                       ├─ simplicity outcome review
SIMP-00 -> SIMP-10 -> SIMP-20 ──────────────────────────────────────────┤
       \-> SIMP-30 (independent backend source owner) ──────────────────┘

LLUP upgrade A/B and embedded product discovery: separate decisions.
```

- Parallel SIMP-20 and SIMP-30 only after separate owners/tests are fixed; do not simultaneously modify a shared contract or JNI-to-Kotlin protocol.
- Any change to the upstream pin invalidates affected runtime/performance evidence; compare it separately instead of attributing results to cleanup.
- Stop or reduce scope when an extraction adds more lifecycle owners, adapters, configuration or unsupported abstractions than it removes.

## Validation and capability

- **Plan-only:** PRODUCT_STRATEGIC discovery + ITERATION + LEAN; no executable code change or runtime PASS claim. Use documentation/navigation consistency checks as available.
- **SIMP-10/20:** PRODUCT_NONE where semantics are unchanged; ITERATION then INTEGRATION; normally SCOPED, automatically STRONG when lifecycle/concurrency/resource contracts are touched. Select exact repository-owned gates via `scripts/detect_ci_scope.py`.
- **SIMP-30 / LLUP:** expected STRONG or FULL if native/JNI, packaging or dependency changes force it. Run native host tests, Android ARM64 build, packaging and affected consumers on exact candidate. Do not weaken tests.
- **SDK public changes:** CIH owns Consumer SDK ABI/Maven publication verification plus direct Hello Harnex and RedactGuard compatibility and Binder/two-APK evidence.
- Deterministic tooling missing locally is **REMOTE_AUTOMATED**; physical Android GGUF/memory/thermal/OEM qualification is **REAL_ENVIRONMENT** and must not be inferred from emulator success.
- Do not merge or assert readiness from a written plan, a green unrelated run or evidence for a different HEAD/base.

## Resume checkpoint

- Read the latest `dev` and related PRs before work; this plan was shaped from `dev@4e5ffda473c87cbb99ab6fb9f4aa75f4e402a477`.
- Current pins: `b9637`/`aedb2a5e…`; Consumer SDK `0.1.0-alpha.11`; generic-text PR #577 is open and supersedes stale #562 only when reconciled.
- The first implementation action is **CIH-10** or read-only **SIMP-00**; no rewrite, v1 pin update or public convenience API has been approved.
- Transfer finished behavior to the canonical code/tests/SDK/memory/architecture owners, update `docs/current-state.md`, then delete this temporary workstream when complete.
