# ARC — Architecture stewardship and modularity

Status: active
Document type: workstream
Owner: repository architecture / module owners
Canonical scope: workstream.architecture-stewardship
Read when: planning a cross-module boundary change, strengthening architecture checks or accepting a structural refactor
Last reviewed: 2026-11-22
Source checkpoint: `dev@4791ab22dafa22c75e303478298ed0619f05519f`

## Goal and decision

**PRODUCT_NONE / governance and behavior-preserving architecture work.** Keep Harnex simple to extend and safe to consume as either a Shared Host/Binder SDK or an embedded Android SDK. Each behavior, policy, state transition and resource has one canonical owner; changes reach consumers through small contracts, not duplicate implementations.

Decision: **NARROW_SCOPE**, not a clean-architecture rewrite, forced package reorganization or module-count reduction. Existing `docs/architecture.md`, `AGENTS.md`, `docs/definition-of-done.md`, `scripts/verify_architecture.py` and module-scoped guides remain canonical. This workstream coordinates the missing measured checks and bounded interventions; it is not a second architecture specification.

Non-goals: introducing Hilt/DI frameworks, arbitrary abstraction layers, second orchestrators, blanket JNI protocol changes, reimplementing LocalMaind chat state, changing Qwen support or product behavior, relaxing security/privacy, renaming packages for aesthetics, treating fewer LOC/modules as success.

## Observed baseline, risks and ownership

Read actual `dev` source and direct consumers before claiming a violation. At this checkpoint Harnex has 36 Gradle modules (including apps), with distinct `core/contracts`, `core/backend-spi`, `core/runtime-core`, `models/*`, `transports/*`, `integrations/*`, backend, evaluation, observability and UI ownership. The architecture verifier guards direct production project dependencies and native `.cpp` includes, with no declared exceptions; it does **not** prove transitive graph hygiene, Kotlin import direction, public ABI stability or lifecycle semantics.

Inspection candidates, **not automatically defects**: `RuntimeOrchestrator.kt` (~1,513 lines), `llama_jni.cpp` (~1,542), phone `MainActivity.kt` (~1,325), and `SecureModelDownloader.kt` (~653). The `simplicity-first-runtime` workstream already analyzed the orchestrator and proved selected cleanup improvements; preserve its KEEP/DEFER decisions. No edit is justified by file size alone.

- **Architectural risk:** broad namespace rules allow unintended intra-family edges or contract leakage; validate a concrete counterexample before restricting legitimate composition.
- **Lifecycle risk:** refactoring cancellation, terminal-once delivery, residency, native handles or Room/AIDL can regress recoverability and ownership.
- **SDK risk:** a local structural improvement may break independently versioned embedded/Consumer Maven clients.
- **Delivery risk:** running full Android/physical gates on documentation-only iterations makes work slow without adding signal.

## Canonical owner routing (do not duplicate work)

| Concern | Owner / existing plan |
| --- | --- |
| Dependency direction, module API exposure, code navigation | `scripts/verify_architecture.py` + tests, `docs/architecture.md`, scoped AGENTS |
| Runtime/JNI cleanup and evidence decision | [SIMP](simplicity-first-runtime.md); SIMP-30 remains blocked pending measurement |
| Embedded API, Maven publication and lifecycle | [EMB](embedded-android-sdk.md), [HLM admission](embedded-context-admission.md) |
| Shared Consumer onboarding/trust | [CIH](consumer-integration-resilience.md), ADR 0018 |
| Harnex product UI/material UX | [phone UI plan](../harness-ux-ui-implementation-plan.md), `apps/local-llm-phone-test/AGENTS.md` |
| LocalMaind chat UI, streaming, attachment history, Activity ownership | [LocalMaind workstream](https://github.com/daniele21/localmaind/blob/main/docs/workstreams/localmaind-chat-v1.md); **not implemented in Harnex** |
| Model download and integrity | `models/model-download`, `models/model-store`, existing model security tests |
| Physical JNI/GGUF/device performance | existing release, EMB and LLRT owners; no new qualification claim here |

## Outcome graph

`ARC-00 -> {ARC-10, ARC-20}`; `ARC-10 + existing EMB/HLM evidence -> ARC-30`; `ARC-00 + demonstrated defect -> ARC-40`; `ARC-10/20/30/40 resolved -> ARC-50`.

| ID / state | Priority, observable outcome and owns/writes | Depends on | Cheap proof / integration exit |
| --- | --- | --- | --- |
| **ARC-00 READY** | **P0.** Reproducible bounded baseline: map direct Gradle edges, public `api` exposure, suspicious same-family imports and change hotspots; compare with owner matrix. **Writes:** architecture guard/tests or one compact finding in this plan, not a second audit ledger. | — | Exact SHA, specific violations/counterexamples, tests or explanation for KEEP; no arbitrary LOC thresholds |
| **ARC-10 READY** | **P0.** Strengthen executable architecture *fitness* only where ARC-00 demonstrates a missed invalid edge (intra-family/layer direction, cycles, SDK/Host independence). Extend `scripts/verify_architecture.py` and `scripts/test_verify_architecture.py` with allowed/forbidden fixtures; keep runtime/Gradle validation cost low. **Writes:** `scripts/` only. | ARC-00 | A failing test for each confirmed violation, existing legitimate edges remain green, `python3 -m unittest scripts/test_verify_architecture.py` and repository health |
| **ARC-20 READY** | **P1.** Improve Harnex phone composition only if a concrete behavior/test burden is documented: move a cohesive Activity-owned operation behind an existing ViewModel/controller or narrow presentation boundary, without moving domain policy into UI. **Writes:** `apps/local-llm-phone-test` tests/source, minimal `ui/design-system` only for reusable visual behavior. | ARC-00; coordinate existing UX owner | Existing journeys unchanged; deterministic ViewModel/Compose tests, lifecycle/navigation proof; `FULL_MEDIA` only if material UX changes |
| **ARC-30 READY** | **P0 for SDK contract changes.** Check published/staged SDK independence with real external fixture and the current LocalMaind `ChatInferencePort` mapping: preview/generate parity, bounded streaming/Stop, version pin, failure/close and no source-project coupling. **Writes:** Harnex SDK contract tests and `samples/external-embedded-android` *only if EMB does not already own the case*. LocalMaind source changes belong in LocalMaind's own PR/workstream. | ARC-10 and current EMB/HLM contract | Independent Maven consumer compile/package/API tests; physical GGUF remains EMB/LM release evidence |
| **ARC-40 BLOCKED** | **P2, conditional.** Extract a native/runtime/download helper only after measurable duplication, an invariant bug or repeated test cost is proven. Preserve one lifecycle/state owner; hand work to SIMP or owning module rather than creating a parallel refactor. **Writes:** existing owner only after decision. | ARC-00 + issue and owner approval | KEEP/EXTRACT/DEFER decision with before/after behavior and regression tests; native edits require native/package gates |
| **ARC-50 READY** | **P1.** Establish repeatable architecture review discipline through the project-local skills below; after successful slices, update canonical docs and retire the temporary plan. **Writes:** `skills/`, root navigation, canonical docs at integration. | ARC-10, ARC-20/30/40 resolved or explicitly deferred to their owners | Skills discoverable; boundary review and refactor decisions evidenced in real PRs; no duplicate owner/obsolete status page |

**Parallel boundaries:** ARC-10 and ARC-20 may proceed independently after ARC-00 because they write separate owners. ARC-30 reuses existing EMB/HLM tests; do not start it as a duplicate feature plan. ARC-40 is not an active coding assignment until unblocked. Integrate coherent independent PRs into `dev` rather than stack branches.

## Repeatable skills (versioned here)

- [`review-module-boundaries`](../../skills/review-module-boundaries/SKILL.md): discover one owner, inspect dependency/contract edges and classify observable architecture risks before module/API changes.
- [`refactor-without-churn`](../../skills/refactor-without-churn/SKILL.md): make a KEEP / EXTRACT / MOVE / DEFER decision with focused regression proof and explicit stop conditions.

These **supplement**, never replace, `structured-change`, `validate-change`, `plan-workstream`, relevant scoped `AGENTS.md` or preflight. They are Harnex-local procedures, not upstream `repo-template-sw` baseline replacements.

## Gates and completion

- **ITERATION / SCOPED:** static fitness unit tests, changed module compile/test; no all-project ritual for plan/skill edits.
- **INTEGRATION:** exact candidate/base, architecture and docs guards, required Android risk-specific gates, direct consumers when public API changes; STRONG for runtime/SDK/lifecycle, `FULL_MEDIA` for material UI changes.
- **RELEASE:** existing real-environment obligations remain blocking where applicable; never infer physical ARM64/GGUF or signing evidence from emulator/build.
- **Success:** structural violations become cheaply detectable, supported consumers continue to work without source coupling, responsibilities remain owned in one place, and no new module/abstraction was added without a demonstrated boundary.
- **Stop:** if evidence says KEEP, stop; do not introduce modules, wrappers, tools or policy duplication merely to improve a metric.

## Resume checkpoint

Checkpoint source: `Harnex dev@4791ab22` and `LocalMaind main@5df6321` (2026-11-22); refresh both heads before code work. This PR establishes documentation/skills only, not passing new fitness gates, runtime tests or qualified Android evidence. Next discriminating action: execute ARC-00 against latest `dev` and produce one concrete missing-guard counterexample, or record that the existing guard covers the observed edges. When complete, transfer only durable decisions to `docs/architecture.md`, tests, and owning plans, then delete this temporary workstream per `skills/finalize-workstream`.
