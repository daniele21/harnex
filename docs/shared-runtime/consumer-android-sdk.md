# Consumer Android SDK publication

Status: active
Document type: feature-specification
Owner: shared-runtime-client
Canonical scope: shared-runtime.consumer-android-sdk
Read when: publishing, versioning, validating or consuming the external Android Consumer SDK artifact
Last reviewed: 2026-09-08

## Public dependency

External Android applications consume one direct coordinate:

```kotlin
implementation("io.github.daniele21.localllm:consumer-android:<version>")
```

The publication carries `core-contracts` and the Binder contract transitively. Consumers must not use `project(...)`, composite builds, git submodules or a Harnex source checkout.

Current candidate: `0.1.0-alpha.11`.

### Runnable onboarding sample

[`../../samples/hello-harnex`](../../samples/hello-harnex/README.md) is the canonical runnable external-app example and the baseline ownership reference for a new Consumer application. It is a standalone Android application that resolves the public Consumer SDK, configures an exact Harnex package/service, exposes its current signing-certificate SHA-256 for Harnex authorization and executes one real assigned local-inference use case.

Use it to understand the smallest production-shaped lifecycle without reading repository internals. The separate `samples/external-consumer-android` project remains the Maven/API-ABI compatibility fixture.

### Recommended Consumer-app ownership

A Consumer app should keep the Harnex boundary narrow and owned by the application:

```text
product UI
   |
   v
lifecycle/state owner (for example ViewModel)
   |
   v
app-owned Harnex client/gateway
   |
   v
consumer-android public SDK
   |
   v
Binder -> Harnex
```

The names are not contractual; the ownership is. Product UI must not become the owner of Binder sessions, model identity or runtime policy. A small app-owned seam is recommended so application behavior can be tested without a live Binder Host and so SDK details do not spread through product code.

For ordinary connection-scoped inference:

- keep the Consumer client outside a transient Activity/Fragment lifetime;
- treat `connect()` / `disconnect()` as reversible transport lifecycle and `close()` as terminal;
- discover only Harnex-assigned use cases/presets rather than hardcoding model/runtime authority into the app;
- release session and activation ownership on success, failure, cancellation and terminal close;
- keep a single canonical owner for active generation handles and reject or deliberately sequence overlapping work;
- preserve typed Consumer/control-plane failures internally and map them to actionable product states such as authorization required, configuration required, model unavailable, transport unavailable or cancelled;
- never persist prompt/output content merely for SDK diagnostics or evidence.

For work that is intended to survive transient UI/connection observation, use `ConsumerLogicalJobClient` and its stable job identity/recovery contract. Do not extend Activity lifetime or create an application-specific pseudo-resume layer around ordinary callback generation.

The golden sample's architecture is intentionally small: `MainActivity -> HelloHarnexViewModel -> HelloHarnexClient -> HelloHarnexRuntime -> consumer-android`. New apps may use Compose, repositories or dependency injection where their product warrants it, but they should preserve the same Harnex ownership boundary rather than copying sample structure mechanically.

## Published artifacts

- `io.github.daniele21.localllm:core-contracts`
- `io.github.daniele21.localllm:android-binder-contract`
- `io.github.daniele21.localllm:consumer-android`

`consumer-android` is the supported direct dependency. The other artifacts preserve ordinary Maven dependency metadata rather than producing a fat AAR.

## Supported boundary

The SDK owns public Consumer contracts, Binder composition, typed transport failures, passive control-plane/readiness inspection and the explicit durable logical-job API. It does not expose model-store/runtime/llama.cpp implementation types and does not grant authorization by itself; package/application/use-case/signing policy remains Harnex-owned.

Consumers bind to the exact configured Harnex package/service component and do not need a custom Harnex bind permission. `SharedRuntimeHostConfig.create` validates Android package and Java service identifiers before binding; supply an explicit trusted component and preserve the real Host package/service pairing. A debug application-ID suffix does not automatically change the Java service class. Harnex public authorization is derived at the Binder boundary from calling UID -> exact installed package -> current signing certificate -> persisted Harnex authorization -> enabled use case. This makes independently signed consumers safe without making installation order or a late-defined Android custom permission part of the public SDK contract.

The Binder client supports reversible `connect()` / `disconnect()` lifecycle control. `disconnect()` detaches the current transport without permanently closing the client, so a consumer can honor an explicit user connection preference and later reconnect with the same SDK instance. A later `connect()` creates a fresh transport/authorization epoch. `close()` remains the terminal lifecycle operation.

Ordinary `prepare/createSession/generate` remains connection-scoped for compatibility. Long-running work that must outlive transient Binder/UI observation opts into `ConsumerLogicalJobClient`:

```kotlin
val response = client.submitLogicalGeneration(
    ConsumerLogicalJobSubmitRequest(
        clientRequestId = ConsumerLogicalJobRequestId("analysis-42-chunk-0"),
        useCaseId = prepared.useCaseId,
        preparedId = prepared.preparedId,
        expectedExecution = prepared.toExecutionIdentity(),
        input = ConsumerGenerationInput.Text(input),
        outputConstraint = ConsumerOutputConstraint.JsonSchema(schema),
    ),
)
```

The accepted job returns a stable `ConsumerInferenceJobId`. After a transport reconnect, authenticated callers use `logicalJob(...)` and `logicalJobResult(...)` with that same ID/use-case instead of submitting duplicate inference. `cancelLogicalJob(...)` is explicit semantic cancellation.

The submit request pins the exact prepared `ConsumerExecutionIdentity`; the Host rejects mismatched scope/configuration rather than silently resolving a durable job against newer capability/preset state. Revisioned query/result snapshots are authoritative after reconnect. Binder callback/endpoint loss is transport loss, not implicit logical-job cancellation.

The logical-job contract is protocol minor 6 (`consumer-logical-jobs-v1`). Setup resolution remains protocol minor 5.

## Privacy and recovery boundary

Logical-job identifiers, revisions, attempts, runtime-session identity and safe error/state metadata are privacy-safe. The SDK contract does not authorize persistence of prompts, document text, findings, generated output, raw Binder payloads or native/KV state.

Host process death remains a truthful native interruption boundary. A later recovery attempt may restart only when the owning workflow still has safe input under its privacy policy; the current Consumer contract does not promise token-exact or sensitive-input-transparent resume.

## Publication verification

Run:

```bash
bash scripts/verify-consumer-sdk-publication.sh
```

The verification publishes release variants to a run-owned local Maven repository under `build/consumer-sdk-repository`, then builds both external consumers from those Maven coordinates only:

- `samples/external-consumer-android` for API/ABI and dependency-surface compatibility;
- `samples/hello-harnex` for runnable onboarding, Consumer lifecycle unit tests and application-boundary integration.

The gate also rejects source/composite/project coupling and writes source-aware manifest/checksum evidence for the published artifacts.

Public API/ABI compatibility is deterministic and already gated. The canonical baseline is:

```text
docs/shared-runtime/consumer-sdk-public-abi.txt
```

Validation generates the current ABI with `scripts/dump-consumer-sdk-abi.sh` and compares it using `scripts/verify-consumer-sdk-abi.sh`. Intentional public changes require inspection of the generated surface and an explicit baseline update; the gate must not be suppressed.

## Publication sequencing

`.github/workflows/publish-consumer-sdk.yml` owns authenticated package publication and the public Maven workflow owns the token-free downstream channel. They validate external consumption and ABI before publishing the Maven artifacts. A push to `dev` that changes `docs/shared-runtime/consumer-sdk-version.txt` resolves the version from that file; workflow dispatch may provide an explicit version where supported.

For the current alpha.11 candidate the correct sequence is:

1. exact-head PR documentation/validation/preflight is green;
2. merge the owning change to `dev`;
3. publish and validate `0.1.0-alpha.11` from that exact `dev` identity;
4. verify source manifest/checksums and public coordinate availability;
5. downstream apps update their normal Maven dependency only after publication succeeds.

Do not treat the pull-request `Consumer SDK validation` workflow as package publication: it uses a run-specific local repository solely to prove external consumption and ABI compatibility.
