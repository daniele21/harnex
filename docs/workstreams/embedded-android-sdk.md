# Embedded Android SDK — independent-app readiness

Status: active
Document type: workstream
Owner: embedded Android integration / external application developers
Canonical scope: workstream.embedded-android-sdk
Read when: preparing Harnex as an in-process Android SDK for independently shipped applications
Last reviewed: 2026-11-19

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

Baseline: `dev@33e76c3` (2026-11-19; refresh before integration). Draft/unmerged parallel lanes: product [#602](https://github.com/daniele21/harnex/pull/602), catalog [#603](https://github.com/daniele21/harnex/pull/603), runtime [#604](https://github.com/daniele21/harnex/pull/604), Maven [#605](https://github.com/daniele21/harnex/pull/605).

Draft [#606](https://github.com/daniele21/harnex/pull/606) combines the lanes, reuses the existing downloader/installer/JNI inspector, and adds a Maven-only standalone sample exercising download/install and two stateless Messages requests. Its script verifies the ARM64 library in both AAR and APK. **These are candidates, not passed external runtime evidence.**

[#605](https://github.com/daniele21/harnex/pull/605) resolved the Gradle task cycle by separating the observability contracts' component identity; its dedicated exact-head Maven staging run succeeded. #606 still needs exact-head integration validation, external APK proof and real GGUF/stream/cancel/teardown evidence on representative physical ARM64. A model-distribution workflow also encountered upstream Maven dependency resolution failure, distinct from product test results. No merge, SDK release or commercial-app handoff is authorized yet.
