# Embedded Android SDK — independent-app readiness

Status: active
Document type: workstream
Owner: embedded Android integration / external application developers
Canonical scope: workstream.embedded-android-sdk
Read when: preparing Harnex as an in-process Android SDK for independently shipped applications
Last reviewed: 2026-11-19

## Product intent and decision

- **Depth:** PRODUCT_STRATEGIC. This makes an embedded, single-app distribution mode a supported public product path alongside, not instead of, Shared Host + Binder Consumer.
- **Consumer / problem:** a commercial Android application must ship one APK and run local inference without requiring its customer to install, authorize or operate a separate Harnex Host. Its developers must not assemble GGUF/JNI/runtime internals.
- **Outcome:** one versioned Gradle dependency lets an independent Android project install/verify a reviewed local model, perform bounded text/message inference with streaming and cancellation, and cleanly release runtime resources.
- **Decision:** BUILD the smallest public embedded adapter over existing runtime/model/backend owners. Keep Shared Host/Binder behavior unchanged. ADR 0010 already establishes the embedded/in-process path; decide any further irreversible public API or security boundary change in its canonical owner before implementing.
- **Non-goals:** chat history, conversation database, turns/truncation/memory policy, chat titles, consumer UX, app identity authorization via Binder, cloud inference/fallback, arbitrary GGUF import, another orchestrator, or a fat APK containing a large model by default.

**Product ownership:** the independent app owns the conversation and chooses the bounded USER/ASSISTANT messages for each turn; Harnex owns chat-template rendering, exact model/profile policy, native session/context, streaming, cancellation, resource and verified-model lifecycle. Use **stateless explicit message input per request** for the first consumer proof; never treat a native session/KV cache as the conversation's source of truth. App-owned instructions must enter through a documented bounded request/configuration policy, not a Harnex chat manager.

**Trust/privacy:** an embedded instance runs inside the commercial app's Android UID/private sandbox; Binder UID/package/signer approval is **specific to the separate Host mode**, not an embedded startup requirement. Preserve content-free normal logs/telemetry and no cloud fallback. Determine an explicit embedded audit persistence default, retention and consent boundary before release: Host Activity's encrypted content store must not be silently copied into an embedded consumer.

## Material risks / assumptions

| Dimension | Level | Risk and cheapest evidence |
| --- | --- | --- |
| VALUE | LOW | Distribution requirement is explicitly established; independent developer fixture must prove one-APK integration |
| USABILITY | HIGH | Native runtime/profile/download setup is too complex today; measure minimal external integration and typed setup/error states |
| FEASIBILITY | HIGH | Native/transitive AAR packaging, ABI, model install, app process lifecycle and real device memory; prove one external packaged artifact end to end |
| VIABILITY | MEDIUM | Public SDK ABI/versioning, Maven dependency graph, supported-model download/license/storage and maintenance cost; audit publication and clean-room consumption |

- **A1 — artifact closure:** the runtime, JNI and their transitive Android libraries can be consumed through a single published Gradle coordinate. **Unproven for an external embedded app.** Check clean standalone compile, release shrink and packaged `arm64-v8a` libraries.
- **A2 — external composition:** Host-only policy/state can be excluded from embedded initialization without copying runtime ownership. **Partially supported** by `transports/in-process`, `docs/api-usage.md` and `apps/device-test-runner`; verify genuine independent run.
- **A3 — message semantics:** `GenerationInput.Messages` is suitable for explicitly supplied bounded multi-turn input with model-native template rendering. **Core support exists; external end-to-end correctness is unproven.** Test 3+ successive message-list requests with no leaked hidden state.
- **A4 — model bootstrap:** reviewed catalog -> secure verified download -> installer -> store can be composed in an independent app while retaining fail-closed policy. **Modules exist; packaged external lifecycle is unproven.** Test absent/interrupted/invalid/installed transitions.
- **A5 — hardware envelope:** representative commercial Android devices can execute the chosen supported model within memory/thermal constraints. **Not established by an emulator.** Capture exact-artifact ARM64/GGUF/lifecycle evidence.

## Canonical owner boundaries

| Work | Canonical owner / reuse |
| --- | --- |
| App-visible embedded facade and Android composition | a small dedicated embedded integration/publishing module only where proven necessary; delegate to `LocalLlmClient` / `InProcessLocalLlmClient` |
| Generation inputs, events, limits, typed errors | `core/contracts` and direct runtime clients; extend only when existing contracts cannot express the agreed behavior |
| Scheduling, context planning, session lifetime, failure/cancel | `core/runtime-core` — **no second runtime owner** |
| GGUF/JNI/native binaries | `backends/llama-cpp`, backend SPI and existing packaging guards |
| Curated model availability, verified download/install/store | `models/model-catalog`, `model-download`, `model-install`, `model-store` and reviewed profiles |
| Embedded audit/log/diagnostic policy | existing observability contracts and optional adapters; do not silently persist application conversation content |
| External clean-room proof | new standalone `samples/external-embedded-android` using published Maven artifacts only |
| Chat turns, history, persistence, UX and business logic | **future commercial-app repository, never Harnex** |

## Work graph (state is planning, not implementation proof)

`EMB-00 -> {EMB-10, EMB-20 design, EMB-30 design} -> EMB-40 -> EMB-50`.

| ID / state | Outcome and focused work | Depends on | Proof / exit |
| --- | --- | --- | --- |
| **EMB-00 READY** | Freeze minimum public embedded use cases: one supported curated text model, app-owned message lists, explicit inference lifecycle, clear readiness/errors, no Host dependency. Decide minimal API, app-supplied instructions/profile limits, model ownership, private storage/audit defaults and which technical setup states SDK exposes. Compare existing `LocalLlmClient` and Consumer contracts before creating interfaces. | — | Short approved contract and ownership decision in canonical docs/tests; no speculative universal generate wrapper |
| **EMB-10 READY** | Publish a minimal `embedded-android` direct Maven coordinate and exact necessary transitive AAR dependencies (including JNI), with correct metadata/consumer shrink rules. Do not build a fat AAR or bundle GGUF. | EMB-00 | Fresh standalone Gradle project resolves AARs from staged Maven without `project(...)`, `includeBuild`, submodule or source checkout; checks release JNI/ABI packaging |
| **EMB-20 READY** | Assemble existing model/store/backend/runtime in the app process behind a bounded owner; provide ready/prepare, stateless `Messages` generation, event streaming, cancellation, cleanup and typed failures. Reuse the existing prompt-template machinery; the app supplies history explicitly. | EMB-00; can proceed with EMB-10 publishing work on non-overlapping files | Deterministic unit/component tests for >1 message, invalid/oversized input, context exhaustion, cancellation/close/reopen, no hidden history or unsafe main-thread work |
| **EMB-30 READY** | Compose curated model discovery and verified HTTPS download/install into app-private storage, with app-facing readiness/progress/error states; no silent selection or unsupported model import. Keep model transfer/install/loading as distinct steps. | EMB-00; parallel to EMB-20 | Tests for unavailable, insufficient space, interrupted/cancelled transfer, digest mismatch, installation failure, reinstall/restart and verified READY; model not shipped in APK |
| **EMB-40 BLOCKED** | Create standalone **external embedded** sample/consumer fixture; use only published artifact, no Host APK/Binder. Prove install -> model ready -> 1st generation -> next turn with prior messages supplied -> streaming/cancel -> app restart/cleanup. Document minimal Gradle/manifest/JNI/proguard setup and ownership. | EMB-10 + EMB-20 + EMB-30 | CI verifies exact staged SDK through external consumer, dependency closure, ABI/public API and APK; no leaked Host/service dependency or prompt content in ordinary logs |
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

- **PRODUCT:** PRODUCT_STRATEGIC; **DELIVERY:** ITERATION per slice, INTEGRATION for a candidate into `dev`, RELEASE only for SDK promotion; **VALIDATION:** STRONG for public contracts/Android/JNI/model lifecycle, repository selector can raise to FULL for build/publishing/toolchain or release; **EXECUTION:** REMOTE_AUTOMATED for Android/Gradle/fixtures when no equivalent local SDK, REAL_ENVIRONMENT for physical GGUF/memory/thermal/device proofs.
- Baseline: use `.engineering/commands.json`, `python3 scripts/detect_ci_scope.py`, existing consumer publication/ABI patterns, model/native scoped AGENTS and available CI/self-hosted runner. No claim of PASS from document changes alone.
- Parallel writes after EMB-00: **publishing/Gradle** (EMB-10), **runtime facade** (EMB-20), **model bootstrap** (EMB-30). Converge once on EMB-40; do not merge prematurely or overlap public contract owners.
- On implementation update `docs/architecture.md` only for changed ownership/deployment, `docs/api-usage.md` and new permanent embedded integration guide for truthful API usage, `docs/product.md` for durable positioning, `docs/current-state.md` for observed integration/blockers. Review ADR 0010 before any architecture divergence. Keep Host/Binder existing SDK tests green.
- **Post-release learning:** can a new independent Android developer integrate a real local generation without reading native internals or seeking Harnex Host setup instructions? Observe setup friction with the first consumer app; no prompt/content telemetry.

## Resume checkpoint

Baseline inspected: `dev@33e76c321bc6cf08046c8ab2e099e2007eb9cc5e`, 2026-11-19; Android validation at this head was green. Recheck target, related PRs and exact-head evidence before any implementation. **No embedded SDK artifact or independent-app runtime evidence was produced by this planning work.**

**Next action:** EMB-00 — choose and test the smallest embedded public API/composition boundary using existing core contracts and the `apps/device-test-runner` proof before introducing new modules.
