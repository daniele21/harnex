# Embedded context admission — LocalMaind SDK enablement

Status: active
Document type: workstream
Owner: Harnex embedded / generation-planning contract
Canonical scope: workstream.embedded-context-admission
Last reviewed: 2026-11-21

This bounded HLM workstream coordinates only the additional **pre-generation admission contract** needed by the first independent [LocalMaind](https://github.com/daniele21/localmaind) consumer. Existing [EMB](embedded-android-sdk.md) owns embedded SDK delivery/packaging and physical handoff; LocalMaind owns history selection, storage, presets UX and chat.

## Product intent, boundary and work graph

**Purpose:** support [LocalMaind](https://github.com/daniele21/localmaind) as the first independent app-owned, persistent offline chat consumer. This section is the **Harnex-side plan**; the LocalMaind domain/UI/storage plan lives in `docs/workstreams/localmaind-chat-v1.md` in the app repository. Avoid a second Harnex runtime or chat manager.

**Product impact:** PRODUCT_FEATURE within the existing embedded strategy. **Consumer/problem:** a mobile app needs a truthful model-aware input budget before it decides which prior messages to include. **Outcome:** a bounded, versioned model/template-aware planning contract without exposing raw prompt, model internals, native context or backend handles. **Non-goals:** chat history, context policy, UX, title generation, summaries, Room conversations, cloud service, new general-purpose sampler UI.

**Risks:** VALUE LOW (app need explicit); USABILITY MEDIUM (preflight failure/limits interpretation); FEASIBILITY HIGH (planning parity and lifecycle/resource side effects); VIABILITY HIGH (public API/ABI and other consumers). Assumption **H-A1**: current loaded-model planning can be reused without allocating a persistent native context or running decode. Falsify via implementation-owner inspection and fake/native parity before adding a public method. Decision: **NARROW_SCOPE** to the smallest verified consumer-facing operation.

| ID / State | Harnex-owned outcome and files | Dependency | Proof / acceptance |
| --- | --- | --- | --- |
| **HLM-00 DONE** | Inspect `GenerationPlanningPolicy`, prompt/tokenization SPI, embedded facade and direct consumers; freeze a minimal preview/capability contract. Do not add a second estimator. Own docs/contract tests. | Existing EMB-20 | Document exact value/error semantics and lack of content logging; test true context overflow is a typed refusal, not silent truncation |
| **HLM-10 ACTIVE** | Implement bounded input admission preview only if HLM-00 confirms safe reuse. Share current model/template/token count/context-tier owner; do not create independent state/cache. `core/contracts`, `core/runtime-core`, `integrations/android-embedded-sdk` as necessary. | HLM-00 | Same request yields same token and eligibility result at prepare/generate; oversized/unsupported/missing-model fail honestly; no queued decode/context leak; direct consumers covered |
| **HLM-20 READY** | Verify effective sampler/settings metadata, request overrides, message-snapshot semantics and fresh technical session per turn; keep #614 owner separate. No new generic sampler knobs without evidence. Own focused tests + external Maven-only consumer fixture. | HLM-00; #614 convergence | Multi-turn explicit messages, Stop, error and reopen; ABI/packaging and Host/Binder remain compatible |
| **HLM-30 BLOCKED** | Hand off an exact versioned embedded SDK artifact to LocalMaind and close physical ARM64/GGUF/latency/PSS/thermal evidence with EMB-40/50. | HLM-10/20, EMB-50 | One independently installed LocalMaind APK with real model and no Host; claim-matched device evidence |

**Integration seam:** LocalMaind sends `GenerationInput.Messages` with explicitly selected complete turns; Harnex performs real template/token/context admission, sampler validation, streaming/cancellation. If preview design cannot provide a truthful result independent of generation, return a typed non-admission/unsupported result and let the app fail closed; **do not** turn Kotlin heuristics into an SDK token estimate.

**Validation:** HLM-00 docs/tests = LEAN/SCOPED; HLM-10 public contract/lifecycle = STRONG with external Maven-only fixture, exact candidate/base; release = FULL, plus blocking REAL_ENVIRONMENT where applicable. Toolchain absence is REMOTE_AUTOMATED; no manual deterministic gate. `dev` remains integration line; no stable/main promotion or release claim by creating this plan.
