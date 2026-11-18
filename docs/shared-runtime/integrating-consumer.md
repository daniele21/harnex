# Integrating a new Android app with Harnex

Status: active
Document type: feature-specification
Owner: shared-runtime-client
Canonical scope: shared-runtime.consumer-integration
Read when: integrating an Android consumer or diagnosing Host connection and readiness
Last reviewed: 2026-10-10

Harnex owns the local GGUF, model selection, residency, authorization and runtime. Your app owns its product workflow and one lifecycle-safe gateway around the public SDK.

## Minimal start

1. Install a supported **Harnex Host** and a curated model. Use the official distribution channel for the intended signer; `dev` and debug APKs are developer fixtures, not interchangeable with signed release APKs.
2. Add the published Maven artifact: `implementation("io.github.daniele21.localllm:consumer-android:0.1.0-alpha.11")`.
3. Use `BinderConsumerLocalLlmClient.create(context.applicationContext, SharedRuntimeHostConfig.create(hostPackage, hostServiceClass), clientBuildId, observer)` with an **explicit trusted component**. Do not scan installed apps or use implicit binding.
4. Call `connect()`, observe `SharedRuntimeConnectionSnapshot`, and proceed only when the transport reports `CONNECTED`. This **does not** prove the app is authorized or any model is ready.
5. On a worker/executor, request Host-assigned use cases and published presets, then `activate`, `prepare`, `createSession`, `generate`. Never embed a GGUF path, Host model policy, or caller-declared authorization.
6. Close session and deactivate on every terminal/error/cancellation path; keep client outside short-lived Activity/Fragment scope. `disconnect()` is reversible; `close()` is terminal.

For a production-shaped standalone reference use [Hello Harnex](../../samples/hello-harnex/README.md). It intentionally keeps one app-owned gateway instead of duplicating Harnex runtime state. The generic-text onboarding candidate in PR #577 provides a TEXT-only, stateless, Host-owned use case; until that PR is integrated, the published Host may still expose only use cases already assigned to the consumer. Do not assume the SDK dependency alone creates a Host assignment.

## Interpret connection and setup separately

| Observed state / typed failure | Safe app response |
| --- | --- |
| `HOST_NOT_INSTALLED` | Guide installation of the exact configured Host; do not bind an arbitrary substitute |
| `BINDING`, `NEGOTIATING` | Wait for the observer's next state; do not block the UI |
| `PERMISSION_DENIED` | Show Host access failure and verify installed package/signing/authorization state; do not bypass the Host |
| `INCOMPATIBLE` | Explain Host/Consumer SDK protocol incompatibility; require an explicitly compatible version |
| `CONNECTION_LOST` | Stop connection-scoped work and offer explicit reconnect; do not blindly replay generation |
| `DISCONNECTED` | Allow deliberate connection; `CLOSED` requires a new client instance |
| `CONNECTED` | Transport only: discover assigned use case and evaluate Host-side setup/readiness |
| `APPLICATION_NOT_AUTHORIZED`, `UNKNOWN_APPLICATION` | Open Harnex Applications, inspect the exact package + signer and authorize explicitly |
| `USE_CASE_NOT_ASSIGNED`, `PRESET_NOT_EXPOSED`, `STALE_REVISION` | Prompt the operator to repair assignment/preset; rediscover revisions, never invent a default |
| `MODEL_UNAVAILABLE`, `MODEL_CONFLICT` | Show model/configuration readiness problem in Host; do not silently choose another model |
| `TRANSPORT_FAILURE`, `FEATURE_UNAVAILABLE` | Surface an unavailable/unsupported capability rather than manufacturing success |

Use the typed results in `ConsumerControlPlaneClient` and optional negotiated `ConsumerRuntimeReadinessClient` instead of parsing free-form error messages. `resolveSetup` is a passive, feature-negotiated inspection of exact published setup identity; it must not activate, load or reserve model resources. Keep connection, authorization, setup, activation and model residency as different states.

## Lifecycle rules that prevent duplicate work

- A regular callback generation is connection-scoped. On transport loss, its terminal outcome may be unknown; do not resubmit automatically.
- For work intentionally detached from UI/connection, negotiate `ConsumerLogicalJobClient` and use stable `ConsumerInferenceJobId`, exact execution identity, query/result after reconnect and explicit semantic cancellation.
- Never assume Harnex process death preserves native decoding. Restarted Host jobs may be `INTERRUPTED`; resubmit only when the consumer workflow still safely owns its input.
- Test Host-first and Consumer-first install order, pending authorization, signer replacement, connection loss, cancellation, and cleanup using the external Maven fixture.

See [Consumer Android SDK contract](consumer-android-sdk.md), [shared-runtime architecture](architecture.md), and [integration resilience workstream](../workstreams/consumer-integration-resilience.md). No cloud or network inference fallback is implied by this guide.
