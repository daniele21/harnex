# Embedded Android SDK — independent-app readiness

Status: active
Document type: workstream
Owner: embedded Android integration / external application developers
Canonical scope: workstream.embedded-android-sdk
Read when: preparing Harnex as an in-process Android SDK for independently shipped applications
Last reviewed: 2026-11-20

## Product intent and decision

- **Depth:** PRODUCT_STRATEGIC; embedded single-app distribution complements Shared Host/Binder.
- **Problem:** a commercial Android app needs local inference in one APK without a separately operated Host or native GGUF/JNI assembly.
- **Outcome:** one versioned Gradle dependency for verified model provisioning, bounded inference, streaming, cancellation and cleanup.
- **Decision:** BUILD a minimal embedded adapter over existing runtime/model/backend owners; preserve Shared Host. ADR 0010 already accepts embedded/in-process deployment.
- **Non-goals:** chat history, conversation database, turns/truncation/memory policy, chat titles, consumer UX, app identity authorization via Binder, cloud inference/fallback, arbitrary GGUF import, another orchestrator, or a fat APK containing a large model by default.

**Ownership:** the app selects USER/ASSISTANT history each turn; Harnex renders model templates and owns native inference/model lifecycle. First proof uses **stateless explicit Messages per request**, not KV/session state as conversation truth. App instructions use bounded configuration/request policy, never a Harnex chat manager.

**Trust/privacy:** embedded runs in the consumer app's sandbox; Binder signer approval applies only to Shared Host. No cloud fallback or content in normal logs. Decide embedded audit opt-in/retention; do not silently copy Host Activity persistence.

## Material risks / assumptions

| Dimension | Level | Risk and cheapest evidence |
| --- | --- | --- |
| VALUE | LOW | Distribution requirement is explicitly established; independent developer fixture must prove one-APK integration |
| USABILITY | HIGH | Native runtime/profile/download setup is too complex today; measure minimal external integration and typed setup/error states |
| FEASIBILITY | HIGH | Native/transitive AAR packaging, ABI, model install, app process lifecycle and real device memory; prove one external packaged artifact end to end |
| VIABILITY | MEDIUM | Public SDK ABI/versioning, Maven dependency graph, supported-model download/license/storage and maintenance cost; audit publication and clean-room consumption |

- **A1:** one Maven coordinate resolves runtime/JNI/transitive dependencies. **Unproven externally.** Check standalone release compile, shrink and ARM64 JNI.
- **A2:** exclude Host-only policy without duplicated runtime. **Partially supported** by in-process/device runner; prove externally.
- **A3:** `GenerationInput.Messages` correctly renders bounded multi-turn input. **Core exists, external proof missing.** Test repeated requests without hidden history.
- **A4:** catalog -> verified download -> install -> store composes externally. **Modules exist, integration unproven.** Test missing/interrupted/corrupt/ready.
- **A5:** supported model operates on representative phones within memory/thermal budgets. **Emulator insufficient.** Capture physical evidence.

## Canonical owner boundaries

| Work | Canonical owner / reuse |
| --- | --- |
| Embedded API/composition | minimal module if necessary; delegate to `LocalLlmClient` / `InProcessLocalLlmClient` |
| Generation inputs, events, limits, typed errors | `core/contracts` and direct runtime clients; extend only when existing contracts cannot express the agreed behavior |
| Scheduling, context planning, session lifetime, failure/cancel | `core/runtime-core` — **no second runtime owner** |
| GGUF/JNI/native binaries | `backends/llama-cpp`, backend SPI and existing packaging guards |
| Curated model availability, verified download/install/store | `models/model-catalog`, `model-download`, `model-install`, `model-store` and reviewed profiles |
| Embedded audit/log/diagnostic policy | existing observability contracts and optional adapters; do not silently persist application conversation content |
| External proof | standalone `samples/external-embedded-android` consuming Maven only |
| Chat/history/UX/business | **commercial-app repository only** |

## Work graph (state is planning, not implementation proof)

`EMB-00 -> {EMB-10, EMB-20 design, EMB-30 design} -> EMB-40 -> EMB-50`.

| ID / state | Outcome and focused work | Depends on | Proof / exit |
| --- | --- | --- | --- |
| **EMB-00 ACTIVE** | Freeze minimum public embedded use cases: one supported curated text model, app-owned message lists, explicit inference lifecycle, clear readiness/errors, no Host dependency. Decide minimal API, app-supplied instructions/profile limits, model ownership, private storage/audit defaults and which technical setup states SDK exposes. Compare existing `LocalLlmClient` and Consumer contracts before creating interfaces. | — | Short approved contract and ownership decision in canonical docs/tests; no speculative universal generate wrapper |
| **EMB-10 ACTIVE** | Publish a minimal `embedded-android` direct Maven coordinate and exact necessary transitive AAR dependencies (including JNI), with correct metadata/consumer shrink rules. Do not build a fat AAR or bundle GGUF. | EMB-00 | Fresh standalone Gradle project resolves AARs from staged Maven without `project(...)`, `includeBuild`, submodule or source checkout; checks release JNI/ABI packaging |
| **EMB-20 ACTIVE** | Assemble existing model/store/backend/runtime in the app process behind a bounded owner; provide ready/prepare, stateless `Messages` generation, event streaming, cancellation, cleanup and typed failures. Reuse the existing prompt-template machinery; the app supplies history explicitly. | EMB-00; can proceed with EMB-10 publishing work on non-overlapping files | Deterministic unit/component tests for >1 message, invalid/oversized input, context exhaustion, cancellation/close/reopen, no hidden history or unsafe main-thread work |
| **EMB-30 ACTIVE** | Compose curated model discovery and verified HTTPS download/install into app-private storage, with app-facing readiness/progress/error states; no silent selection or unsupported model import. Keep model transfer/install/loading as distinct steps. | EMB-00; parallel to EMB-20 | Tests for unavailable, insufficient space, interrupted/cancelled transfer, digest mismatch, installation failure, reinstall/restart and verified READY; model not shipped in APK |
| **EMB-40 ACTIVE — FIXTURE CANDIDATE** | Create standalone **external embedded** sample/consumer fixture; use only published artifact, no Host APK/Binder. Prove install -> model ready -> 1st generation -> next turn with prior messages supplied -> streaming/cancel -> app restart/cleanup. Document minimal Gradle/manifest/JNI/proguard setup and ownership. | EMB-10 + EMB-20 + EMB-30 | CI verifies exact staged SDK through external consumer, dependency closure, ABI/public API and APK; no leaked Host/service dependency or prompt content in ordinary logs |
| **EMB-50 BLOCKED** | Qualify first independent-app integration candidate and publish a clearly pre-release, versioned embedded SDK. Distinguish developer integration readiness from commercial release readiness. | EMB-40 | Repository-selected STRONG/FULL gates as appropriate; exact-head clean external build, representative physical `arm64-v8a` with real supported GGUF, streaming/cancel, process/memory and storage lifecycle; explicit unsupported-device and remaining thermal/quality caveats |

## LocalMaind consumer enablement — cross-repository coordination

**Purpose:** support [LocalMaind](https://github.com/daniele21/localmaind) as the first independent app-owned, persistent offline chat consumer. This section is the **Harnex-side plan**; the LocalMaind domain/UI/storage plan lives in `docs/workstreams/localmaind-chat-v1.md` in the app repository. Avoid a second Harnex runtime or chat manager.

**Product impact:** PRODUCT_FEATURE within the existing embedded strategy. **Consumer/problem:** a mobile app needs a truthful model-aware input budget before it decides which prior messages to include. **Outcome:** a bounded, versioned model/template-aware planning contract without exposing raw prompt, model internals, native context or backend handles. **Non-goals:** chat history, context policy, UX, title generation, summaries, Room conversations, cloud service, new general-purpose sampler UI.

**Risks:** VALUE LOW (app need explicit); USABILITY MEDIUM (preflight failure/limits interpretation); FEASIBILITY HIGH (planning parity and lifecycle/resource side effects); VIABILITY HIGH (public API/ABI and other consumers). Assumption **H-A1**: current loaded-model planning can be reused without allocating a persistent native context or running decode. Falsify via implementation-owner inspection and fake/native parity before adding a public method. Decision: **NARROW_SCOPE** to the smallest verified consumer-facing operation.

| ID / State | Harnex-owned outcome and files | Dependency | Proof / acceptance |
| --- | --- | --- | --- |
| **HLM-00 ACTIVE** | Inspect `GenerationPlanningPolicy`, prompt/tokenization SPI, embedded facade and direct consumers; freeze a minimal preview/capability contract. Do not add a second estimator. Own docs/contract tests. | Existing EMB-20 | Document exact value/error semantics and lack of content logging; test true context overflow is a typed refusal, not silent truncation |
| **HLM-10 READY** | Implement bounded input admission preview only if HLM-00 confirms safe reuse. Share current model/template/token count/context-tier owner; do not create independent state/cache. `core/contracts`, `core/runtime-core`, `integrations/android-embedded-sdk` as necessary. | HLM-00 | Same request yields same token and eligibility result at prepare/generate; oversized/unsupported/missing-model fail honestly; no queued decode/context leak; direct consumers covered |
| **HLM-20 READY** | Verify effective sampler/settings metadata, request overrides, message-snapshot semantics and fresh technical session per turn; keep #614 owner separate. No new generic sampler knobs without evidence. Own focused tests + external Maven-only consumer fixture. | HLM-00; #614 convergence | Multi-turn explicit messages, Stop, error and reopen; ABI/packaging and Host/Binder remain compatible |
| **HLM-30 BLOCKED** | Hand off an exact versioned embedded SDK artifact to LocalMaind and close physical ARM64/GGUF/latency/PSS/thermal evidence with EMB-40/50. | HLM-10/20, EMB-50 | One independently installed LocalMaind APK with real model and no Host; claim-matched device evidence |

**Integration seam:** LocalMaind sends `GenerationInput.Messages` with explicitly selected complete turns; Harnex performs real template/token/context admission, sampler validation, streaming/cancellation. If preview design cannot provide a truthful result independent of generation, return a typed non-admission/unsupported result and let the app fail closed; **do not** turn Kotlin heuristics into an SDK token estimate.

**Validation:** HLM-00 docs/tests = LEAN/SCOPED; HLM-10 public contract/lifecycle = STRONG with external Maven-only fixture, exact candidate/base; release = FULL, plus blocking REAL_ENVIRONMENT where applicable. Toolchain absence is REMOTE_AUTOMATED; no manual deterministic gate. `dev` remains integration line; no stable/main promotion or release claim by creating this plan.

## Critical acceptance / handoff to commercial app

**READY TO START EXTERNAL CHAT REPO** only after the documented **exact SDK version** can be independently resolved and the standalone sample, without Host, demonstrates:

1. one app installation; supported curated model can be provisioned in its own sandbox and used offline thereafter;
2. input `Messages(USER, ASSISTANT, USER)` produces a real streamed local response, and the next request receives **only** the history the app sends;
3. cancellation, model-not-ready, context-too-large and app/process teardown have truthful typed outcomes and no resource leaks or unsafe automatic replay;
4. release AAR dependency and native `arm64-v8a` packaging work from staged/public Maven; Android integration evidence is tied to a precise revision/artifact;
5. one representative physical ARM64 + supported GGUF path has passed, with any wider device/support, thermal, security or commercial-release claims left explicitly unproven.

Passing this gate authorizes **integration work on a new app**, not a claim that an application is production-ready, universally device-compatible or commercially validated. The commercial app then owns UI, persisted chat history, context selection/truncation and any later cloud or sync feature.

## Validation, execution and delivery

- **Axes:** PRODUCT_STRATEGIC; ITERATION -> INTEGRATION (`dev`) -> RELEASE (SDK promotion); STRONG for public/Android/JNI contracts, FULL where selected for build/release; REMOTE_AUTOMATED for missing Android tooling and REAL_ENVIRONMENT for physical ARM64/GGUF/memory/thermal evidence.
- Baseline: use `.engineering/commands.json`, `python3 scripts/detect_ci_scope.py`, existing consumer publication/ABI patterns, model/native scoped AGENTS and available CI/self-hosted runner. No claim of PASS from document changes alone.
- Parallel writes after EMB-00: **publishing/Gradle** (EMB-10), **runtime facade** (EMB-20), **model bootstrap** (EMB-30). Converge once on EMB-40; do not merge prematurely or overlap public contract owners.
- Update relevant canonical architecture/API/integration docs and current-state as implemented; review ADR 0010 for changed decisions. Keep Host/Binder regressions green.
- **Post-release learning:** can a new independent Android developer integrate a real local generation without reading native internals or seeking Harnex Host setup instructions? Observe setup friction with the first consumer app; no prompt/content telemetry.

## Resume checkpoint

**Development integration:** [PR #606](https://github.com/daniele21/harnex/pull/606) integrates the reviewed model policy, stateless embedded runtime, verified download/install, Maven publishing and standalone external-APK fixture into `dev`. The build-only fixture consumes staged versioned Maven artifacts without Host/Binder or source-project dependency and checks `arm64-v8a/liblocal_llm_jni.so` in the native AAR and APK; JNI extraction is explicitly configured. Existing model/runtime owners are reused rather than duplicated. Its independent automated Maven/APK check passed on the candidate, with integration gates required for the final head/base.

**Interactive fixture:** [PR #609](https://github.com/daniele21/harnex/pull/609) adds app-owned in-memory chat, bounded Messages, streaming/Stop, timing/PSS, tests and initial emulator UI proof. Prior physical GGUF generation was user-reported for the fixed-prompt APK; new multi-turn/cancel/restart, resource and thermal evidence remains open.

**Open release/handoff gates:** an actual ARM64 device must prove the real pinned GGUF download/install, native streaming, follow-up Messages supplied explicitly, cancellation, restarts, cleanup and resource behavior; the external fixture currently offers a manual smoke action rather than a passing physical result. Stable/public Maven publication and compatibility/ABI release acceptance also remain open. `dev` integration is not an embedded SDK release or authorization to start the commercial chat app; keep EMB-40/50 active until the evidence is recorded.
