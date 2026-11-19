# SIMP — Simplicity-first Harnex runtime

Status: active
Document type: workstream
Owner: runtime-core / llama-cpp backend (coordination; no duplicate implementation owners)
Canonical scope: workstream.simplicity-first-runtime
Read when: choosing the next evidence-backed simplification of the Harnex runtime
Last reviewed: 2026-11-19
Source checkpoint: `dev@7057690134260a59de9b74e38e058a582eff01f3`

## Goal and boundaries

- **Decision:** NARROW_SCOPE. Preserve one-decode scheduling, bounded residency and fail-closed shared Host. Simplify only proven duplication and invariant violations; do not optimize for line count.
- **Product axis:** PRODUCT_NONE for behavior-preserving runtime cleanup; PRODUCT_FEATURE for SDK ease of use; embedded distribution/model support would be PRODUCT_STRATEGIC.
- **Consumer/job:** Android developers need dependable local inference without owning native lifecycle; maintainers need one explicit owner per resource and terminal transition.
- **Non-goals:** rewrite, second orchestration manager, new agent/RAG platform, unbounded residency, automatic retry, silent cloud fallback, generic module collapse, speculative JNI protocol changes.
- **Risks:** VALUE medium (actual integrator burden still unmeasured), USABILITY high (authorization/readiness semantics), FEASIBILITY high (native memory/concurrency), VIABILITY medium (SDK/RedactGuard compatibility). Measure integration effort rather than infer it from LOC.
- **Acceptance:** preserve lifecycle, session isolation, bounded/cancellable resources, single terminal, explicit failure, privacy-safe diagnostics, backend/model identity and direct-consumer compatibility.

## Canonical owners and open decisions

| Scope | Owner and decision |
| --- | --- |
| Model/context physical transitions and coordination | `RuntimeOrchestrator`; no extra manager |
| Decode queue/cancellation | `SingleDecodeScheduler` |
| Session reservation/close | `SessionLifecycle` |
| Resident model handle | `ModelResidencyLifecycle` |
| Deterministic resolved configuration | `GenerationPlanningPolicy` / `ResolvedRequestConfiguration` |
| Consumer setup/recovery (C1) | CIH: `docs/workstreams/consumer-integration-resilience.md`; no public API until independent consumer value is demonstrated |
| Memory admission/physical limits (C2) | MEM/Q35; no `MEASURED` production profile implied by deterministic tests |
| JNI data protocol (C4, SIMP-30) | BLOCKED pending cost/maintainability measurement; Base64 protects token-fragment transport across JNI; positional responses alone are not a reason to rewrite |
| `llama.cpp` source pin and model qualification | LLUP/LLRT; production pin `b9637`. Upstream v1.0.0 not certified for Android/JNI/GGUF; don't conflate source compatibility and release evidence |
| Embedded SDK, module count | No change without demonstrated consumer need or measured cost |

## Integrated changes — evidence is in the PRs/tests

| Slice | Status | Outcome / evidence |
| --- | --- | --- |
| SIMP-00/10/20 | DONE | Existing owner map and guard/terminal-once refactor, PR #584 |
| SIMP-25 | DONE | One batch failure-cleanup path, PR #591, `dev@dfa1675` |
| SIMP-26 | DONE | Batch native release failure preserves `DEGRADED`, PR #592, `dev@48ea57d`; no automatic recovery policy |
| SIMP-27 | DONE | Ordinary preparation/streaming/finalization readable within one owner, PR #594, `dev@d5fb979` |
| SIMP-28 | DONE | Shared immutable metadata/backend request projections; separate batch/ordinary lifecycle, PR #596, `dev@ed7aa303` |
| SIMP-29 | IN REVIEW | Audit and truthful health on failed deferred ordinary-session release, PR #598; exact-head integration STRONG pending |
| SIMP-30 | BLOCKED | JNI protocol investigation: KEEP until measurable justification and native/device evidence |

## SIMP-29 — residual-complexity audit and change

Source: `dev@70576901`, `RuntimeOrchestrator.kt` 1,404 lines. Source-line spans (not formal cyclomatic complexity): `executeEvaluationBatch` 115; `generateEvaluationBatch` 89; `streamGeneration` 84; `prepareGeneration` 57; `prepareEvaluationBatchCase` 44.

- **KEEP separate:** batch owns its dedicated native context, per-case cancellation/attribution and disabled thinking; ordinary generation owns streaming, guard stops and explicit cancellation barriers. Consolidating the similar prompt/context planning blocks would add callback/checkpoint complexity without a proven maintenance benefit.
- **Smallest proven change:** the two worker `finally` blocks repeated deferred-unload/READY/DEGRADED state logic. When a deferred `releaseSession` failed during an ordinary terminal, it marked `DEGRADED` and then the ordinary `finally` overwrote it with `READY`. One in-class finalization helper now respects an existing `DEGRADED` state; batch retains its release-failure precedence.
- **Regression:** after the decode worker is idle, failed ordinary native context release remains `DEGRADED`, produces one terminal, attempts release once and allows a later explicit session-close retry. Existing batch cleanup, guard, queue/cancel, streaming and memory tests must remain green.
- **Limit:** truthful health reporting is **not** recovery. A later independent prepare/retry and physical native qualifications are separate policy/evidence questions. Do not start further refactors merely to lower LOC.

## Validation and completion

- **Product:** PRODUCT_NONE. **Delivery:** ITERATION -> INTEGRATION. **Validation:** STRONG for native-context cleanup, concurrency and lifecycle. **Execution:** REMOTE_AUTOMATED for deterministic Android gates; REAL_ENVIRONMENT for applicable physical GGUF/memory/thermal/OEM/signing evidence.
- At integration: exact candidate/base, reviewed full diff, Android STRONG runtime regressions and direct consumer compilations, Repository validation, formatting/Detekt, canonical docs. Never turn an unrelated green run or emulator proof into native/device readiness.
- **Stop rule:** don't add state owners, adapters, suppressions or abstractions that cost more than they remove. Let completed details live in code/tests/PR evidence; keep this workstream focused on open decisions and remove it when truly complete.
