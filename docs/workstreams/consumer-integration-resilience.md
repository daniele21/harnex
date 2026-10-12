# Consumer integration resilience

Status: active
Document type: workstream
Owner: shared-runtime-client / external consumers
Canonical scope: workstream.consumer-integration
Read when: simplifying public Consumer SDK onboarding, connection/setup failure handling, session lifecycle recipes or external-consumer proof
Last reviewed: 2026-10-10

## Product intent

- **Depth:** PRODUCT_FEATURE for a meaningfully simpler supported external-app integration experience; internal tests/docs-only slices remain PRODUCT_NONE.
- **Consumer / problem:** Android teams want Harnex inference without writing and maintaining Binder, model, authorization and resource policy. The current golden sample still coordinates `assignedUseCases -> publishedPresets -> activate -> prepare -> createSession -> generate -> closeSession/deactivate`; connection `CONNECTED` only means transport registration, not authorized/ready inference.
- **Outcome:** a new app integrates with public Maven Consumer SDK through one clear, testable app-owned gateway, receives actionable typed setup/recovery states and can cancel/close/reconnect without leaks or hidden inference replay.
- **Non-goals:** no silent Host installation, cloud fallback, direct GGUF/model authority in consumers, auto-authorization, automatic replay of accepted generations, new shared model/runtime owner, or universal GGUF/device support.
- **Evidence / assumptions:** RedactGuard and Hello Harnex prove the separate-app boundary but are first-party; external onboarding effort and preferred convenience abstraction remain unknown. PR #577 reconciles the generic Hello Harnex use case and create-connection feedback from the older #562 with the current Host integration baseline; preserve its scope.
- **Risks:** VALUE medium (integration friction is visible in sample, broader adoption unmeasured); USABILITY high (transport-ready vs authorized/model-ready confusion); FEASIBILITY medium (async Binder callbacks, lifetimes, cancellation); VIABILITY medium (public API/ABI compatibility and publication).
- **Decision:** NARROW_SCOPE: first simplify the supported integration recipe and error guidance using existing APIs, test failure/lifecycle behavior and measure remaining friction; introduce a small additive SDK convenience API only if independent consumers demonstrably duplicate the same stable semantics.
- **Success — acceptance:** sample/fixtures run against published artifact, typed Host absence/denied/setup/model/transport failure handling, no duplicate inference, one owner for sessions/activations, bounded cleanup on cancel/close/reconnect.
- **Success — outcome:** external developers can reach an authorized real local generation without reading Harnex runtime/JNI internals or implementing Harnex-owned model policy. Observe time-to-first-inference and support questions with consenting external evaluators, without content telemetry.
- **Post-release question:** what repeated integration steps still require domain-independent boilerplate in at least two independent consumer apps?

## Canonical boundaries and invariants

- `transports/android-binder-client` owns binding, connection epochs and typed transport outcomes; `integrations/android-service-host` owns verified caller, resource ownership and Host Binder delegation.
- `core/contracts` owns public consumer/control-plane/readiness semantics. Prefer `assignedUseCases`, `publishedPresets`, `resolveSetup` (passive, feature-negotiated), `runtimeReadiness`, and exact `activate/prepare` over parallel selection policy.
- Consumer app owns product UI, one client/gateway lifetime, explicit user Connect/Disconnect preference, stable job identifiers when using the negotiated logical-job API, and its safe input/recovery policy.
- Harnex Host owns authorization, model, use-case, preset, session/backend/runtime policies. Never infer readiness from transport `CONNECTED`.
- Blocking SDK operations run off Android main thread. Generation callbacks do not acquire UI/Host policy ownership.
- A failed `connect()` may be observed asynchronously. Transport loss never implies accepted generation can be blindly resubmitted; durable jobs use negotiated `ConsumerLogicalJobClient` query/result and exact identity.
- Cleanup of activation/session is idempotent, awaited or serialized according to the owning lifecycle; failure of one cleanup step must not silently skip the next.
- No prompts, generated content, signing secrets, private paths, or document text enter ordinary diagnostics.
- Existing Consumer API/ABI and external Maven integration remain authoritative; no new public SDK surface until contract/ABI and RedactGuard/sample compatibility proof.

## Work graph

| ID | Work / exact owner | Depends | State | Observable exit evidence |
| --- | --- | --- | --- | --- |
| CIH-00 | Audit current SDK/sample/RedactGuard journey; map minimal public operations and setup failures. Docs/workstream only | none | DONE | Existing canonical owners, overlap #562 and friction points identified |
| CIH-10 | Document a short correct golden path and recovery table. `docs/shared-runtime/*`; no sample behavior change | CIH-00 | DONE | Host absence, auth pending, model not ready, loss, incompatible version, explicit disconnect all have precise next actions |
| CIH-20 | Consolidate actionable connection/setup states with existing public types. `transports/android-binder-client`, `core/contracts` only if needed | CIH-10 | READY | Pure tests distinguish connection from readiness; no new authoritative state or silent retry |
| CIH-30 | Reduce repeated domain-neutral session boilerplate only where proved; `samples/hello-harnex` AFTER PR #577 converges | CIH-20, #577 integration | BLOCKED | Atomic lifecycle, cancel/close/retry error outcomes proven; no duplicate model/preset policy |
| CIH-40 | Cross-app consumer proof / regression. `samples/external-consumer-android` and `apps/shared-runtime-client-consumer-fixture`; RedactGuard direct consumer proof | CIH-20/30 as applicable | READY | Published Maven SDK, ABI, host missing, pending/denied, reconnect, replace signer, service loss and cancellation covered |
| CIH-50 | On-device Host/Consumer qualification on exact signed candidates; existing SR-6/HBG physical runner and release owners | CIH-40 | BLOCKED | Claim-matched distinct-signer and ARM64/JNI/GGUF evidence without overclaiming emulator tests |

## Parallelization and convergence

- Lane A (docs / integration guidance): CIH-10 independently from native/runtime RA work.
- Lane B (SDK transport/contract): CIH-20 after the failure/readiness inventory; avoid touching Host authorization policy without evidence.
- Lane C (external consumer tests): CIH-40 can add independent failure fixtures early but converges with CIH-20/30 before claiming integrated behavior.
- Lane D (generic golden sample): PR #577 owns `samples/hello-harnex` and Host generic use-case changes; the older PR #562 remains stale and must not be merged independently. Do not create overlapping sample branches.
- CIH-50 is distinct REAL_ENVIRONMENT release proof, never an automatable gate delegated to the user merely because local tooling is unavailable.

Use `ITERATION` narrow owner-local tests; `INTEGRATION` exact-head/base scope detection, direct-consumer/SDK publication/API-ABI gate, affected emulator Binder E2E; `RELEASE` FULL and all applicable blocking physical evidence. No broad UI redesign is assumed.

## Resume checkpoint

- Start from current green `dev`, not the historical Harnex 0.5/main snapshots. Recheck base and competing PRs before each slice.
- Existing SDK: `consumer-android:0.1.0-alpha.11`; verified Maven consumer fixture, generic API/capability work is tracked by the reconciled PR #577.
- The smallest next executable slice is CIH-10 plus focused transport/no-content-leak validation where applicable, not a new one-call wrapper invented before consumer evidence.
- Keep completed technical truth in `docs/shared-runtime/consumer-android-sdk.md`, the golden sample and regression tests. Delete this temporary workstream after stable owners have absorbed the results.
