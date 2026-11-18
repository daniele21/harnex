# Harnex samples

Samples demonstrate public consumption boundaries without making repository-internal modules part of the integration contract.

## Available

### `hello-harnex`

The **runnable onboarding sample and golden Consumer-app reference** for external Android developers. It is a standalone app that consumes the published Consumer SDK, shows its exact package/signer identity, guides the user through Harnex authorization and runs the host-owned bounded `Generic text generation` capability through Binder.

Start here if you want to answer: **“How should another Android app actually integrate Harnex?”**

```bash
./gradlew -p samples/hello-harnex :app:installDebug
```

See [`hello-harnex/README.md`](hello-harnex/README.md) for the runnable flow and reference ownership pattern.

### `external-consumer-android`

A standalone Gradle project used to prove that the published Consumer Android SDK can be consumed through normal Maven coordinates without a Harnex source checkout, composite build or project dependency.

It validates the external API/ABI and dependency surface. It is intentionally a **compatibility fixture**, not a polished product demo application.

Public dependency:

```kotlin
implementation("io.github.daniele21.localllm:consumer-android:0.1.0-alpha.11")
```

For the complete Consumer contract, lifecycle and authorization model, see [`../docs/shared-runtime/consumer-android-sdk.md`](../docs/shared-runtime/consumer-android-sdk.md).

## Sample design rules

A Harnex Consumer sample should:

- depend only on supported public artifacts/contracts;
- keep Harnex runtime/model policy out of consumer product code;
- keep Binder/SDK details behind one small app-owned boundary rather than spreading them through UI/product code;
- keep ordinary Consumer client ownership outside a transient Activity/Fragment lifetime;
- demonstrate explicit connect/disconnect/close lifecycle where applicable;
- release sessions, activations, handles and other app-owned resources on every terminal/close path;
- translate typed SDK failures into product-actionable states without hiding diagnostic codes;
- avoid persisting prompts or outputs as diagnostics/evidence;
- fail closed when authorization or negotiated capability is unavailable;
- include focused tests for lifecycle/failure behavior, not only a successful compile;
- remain small enough that the Harnex integration boundary is obvious.

For work designed to outlive transient UI/transport observation, use the public durable logical-job API rather than extending Activity lifetime or inventing app-local recovery semantics.

`hello-harnex` owns developer onboarding and the recommended Consumer ownership pattern; `external-consumer-android` owns Maven/API compatibility proof. Do not merge those responsibilities into one hidden test harness.
