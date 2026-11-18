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
- **Evidence / assumptions:** RedactGuard and Hello Harnex prove the separate-app boundary but are first-party; external onboarding effort and preferred convenience abstraction remain unknown. PR #577 integrated a bounded Host-owned generic-text use case, Hello Harnex sample and connection-creation feedback on `dev`. The older #562 was closed as superseded. This proves a concrete generic onboarding path, not an already released public Host build or a one-call SDK.
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
| CIH-20 | Consolidate actionable connection/setup states with existing public types. `transports/android-binder-client`, `core/contracts` only if needed | CIH-10 | ACTIVE | Pure tests distinguish connection from readiness; no new authoritative state or silent retry |
| CIH-30 | Reduce repeated domain-neutral session boilerplate only where proved; `samples/hello-harnex` on canonical integrated generic-text use case | CIH-20 | ACTIVE | Atomic lifecycle, cancel/close/retry error outcomes proven; no duplicate model/preset policy |
| CIH-40 | Cross-app consumer proof / regression. `samples/external-consumer-android` and `apps/shared-runtime-client-consumer-fixture`; RedactGuard direct consumer proof | CIH-20/30 as applicable | READY | Published Maven SDK, ABI, host missing, pending/denied, reconnect, replace signer, service loss and cancellation covered |
| CIH-50 | On-device Host/Consumer qualification on exact signed candidates; existing SR-6/HBG physical runner and release owners | CIH-40 | BLOCKED | Claim-matched distinct-signer and ARM64/JNI/GGUF evidence without overclaiming emulator tests |

## CIH-30 discovery checkpoint — two real consumer codepaths

Reviewed Harnex `dev@a3c3d432` and RedactGuard `main@59a4a7a`. This is source-level evidence, not measured external developer time.

| Concern | Hello Harnex | RedactGuard | Reuse decision |
| --- | --- | --- | --- |
| Discover assigned use case and published presets | Yes; fixed default text preset | Yes; process-local operator selection with stale-value replacement | Same Host contract; app-owned selection policy differs |
| Verify assignment/binding and activation identities | Yes | Yes, with explicit failure diagnostics | Candidate for a small pure-contract helper only when adoption justifies an SDK ABI change |
| Prepare exact execution | Generic TEXT / stateless / reasoning disabled | Domain JSON_SCHEMA / stateless / reasoning disabled + capability revision | Explicitly pin and check preparation using existing types; keep domain constraints separate |
| Execution and recovery | One callback generation, connection-scoped cancellation | Durable logical jobs, stable identity/query after disconnect | **Do not** merge or hide different recovery semantics under one `generate()` |
| Cleanup | One session and activation per short generation | Multiple document chunks and logical jobs with domain cancellation acknowledgements | Keep per-consumer lifecycle ownership; no second policy/state manager |

**Decision: NARROW_SCOPE.** A universal public `generate()` facade would add another lifecycle/ABI owner while hiding a real difference in recovery semantics; no product evidence currently justifies it. Instead prove a correct exact-preset short-text example with the existing Consumer SDK, then assess whether third-party consumers repeat *pure, domain-neutral* setup identity boilerplate. Counts of SDK operations are not an integration-time measurement. Product impact remains unmeasured.

### First simplification slice — app-owned SDK seam

The Hello Harnex sample previously repeated the SDK's control-plane and local-inference method signatures and forwarded each method through a redundant abstract intermediate class. Replace this with the same two public SDK interfaces via Kotlin `by` delegation, retaining the app's explicit `connect/disconnect/close` lifecycle and its injectable test seam. This removes boilerplate **without changing** SDK ABI, Host authorization, inference policy, or job/recovery semantics. The sample's existing lifecycle/authorization/terminal-once tests and Maven publication-consumer fixture must remain green. This is a **sample-code maintenance improvement**, not proof that an independent Android integrator's time-to-first-inference has improved.

Do **not** introduce an additive public convenience API or `ConsumerSetupIdentity` type merely for the two first-party consumers: that would change the versioned ABI baseline for alpha.11. Qualify actual independent demand and compatibility before opting into a published SDK release.

## Parallelization and convergence

- Lane A (docs / integration guidance): CIH-10 independently from native/runtime RA work.
- Lane B (SDK transport/contract): CIH-20 after the failure/readiness inventory; avoid touching Host authorization policy without evidence.
- Lane C (external consumer tests): CIH-40 can add independent failure fixtures early but converges with CIH-20/30 before claiming integrated behavior.
- Lane D (generic golden sample): PR #577 is merged; treat `samples/hello-harnex` and its Host-owned text capability as the canonical onboarding source. Do not revive superseded PR #562 or duplicate this application logic.
- CIH-50 is distinct REAL_ENVIRONMENT release proof, never an automatable gate delegated to the user merely because local tooling is unavailable.

Use `ITERATION` narrow owner-local tests; `INTEGRATION` exact-head/base scope detection, direct-consumer/SDK publication/API-ABI gate, affected emulator Binder E2E; `RELEASE` FULL and all applicable blocking physical evidence. No broad UI redesign is assumed.

## Resume checkpoint

- Start from current green `dev`, not the historical Harnex 0.5/main snapshots. Recheck base and competing PRs before each slice.
- Existing SDK: `consumer-android:0.1.0-alpha.11`; verified Maven consumer fixture; generic text generation is integrated through PR #577 on `dev`, not certified for older public Host artifacts.
- CIH-10's existing short golden path and failure/recovery table are complete. PR #578 integrated the blank-epoch invalidation fix in `dev@c2ab02d`, validated with Android STRONG and external Maven Consumer checks. CIH-20 is auditing the remaining typed setup/transport states. The current slice closes the race where an endpoint becomes stale between lookup and dispatch (same behavior already used by logical jobs), using direct adapter tests. Other readiness/failure paths must still be reviewed before any public convenience API.
- Keep completed technical truth in `docs/shared-runtime/consumer-android-sdk.md`, the golden sample and regression tests. Delete this temporary workstream after stable owners have absorbed the results.
