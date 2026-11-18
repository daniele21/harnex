# Hello Harnex

A runnable standalone Android app and the **golden reference for a new Harnex Consumer app**.

It proves the public path end to end:

```text
Hello Harnex app
  -> app-owned Consumer boundary
  -> Consumer Android SDK
  -> explicit Binder host
  -> Harnex authorization + Generic text generation
  -> shared local runtime
  -> llama.cpp / local GGUF
  -> text result back to the app
```

The sample does **not** depend on Harnex source modules, JNI code, GGUF files or model-store internals. It resolves `consumer-android` from the public token-free Maven channel just like an external repository.

## Reference architecture

Keep a new Consumer app small at the Harnex boundary:

```text
MainActivity / product UI
        |
        v
HelloHarnexViewModel
  product-visible state
  survives Activity recreation
        |
        v
HelloHarnexClient
  connection + assigned capability lifecycle
  activation / prepare / session / generation / cleanup
        |
        v
HelloHarnexRuntime
  tiny app-owned seam around the public SDK
        |
        v
consumer-android -> Binder -> Harnex
```

This is intentionally **not** a reusable framework hidden inside the sample. The important pattern is ownership:

- UI renders product state and sends user intents;
- a lifecycle owner keeps the Consumer client out of the Activity lifetime;
- one small app-owned boundary isolates the public SDK from product code and makes behavior testable;
- Harnex owns authorization, model identity, selection, residency and runtime policy;
- every accepted execution releases session and activation on terminal paths;
- `disconnect()` is reversible, while `close()` is terminal;
- prompts and generated output are not persisted or written to diagnostics by this sample.

`Generic text generation` exists to make the first integration easy to understand. Product apps should still use narrower host-owned use cases when their workflow needs stronger output, reasoning, schema or policy semantics.

## What you need

- JDK 17 and Android SDK API 36;
- an emulator or Android device visible to `adb`;
- Harnex installed;
- a supported local model installed in Harnex for the `Generic text generation` use case.

The sample defaults to the source-build Harnex package `io.github.daniele21.localllm.phonetest.debug`. If you are using the Play/release package, use the override shown below.

## 1. Run Harnex

From the repository root, for the normal local-development path:

```bash
bash scripts/run-emulator-debug.sh --app phone-test
```

Install a supported model from **Models** if needed.

## 2. Install Hello Harnex

From the repository root:

```bash
./gradlew -p samples/hello-harnex :app:installDebug
```

For the Play/release Harnex package instead:

```bash
./gradlew -p samples/hello-harnex \
  -PharnexHostPackage=io.github.daniele21.localllm.phonetest \
  :app:installDebug
```

Open **Hello Harnex** on the device.

## 3. Authorize the exact app identity

The sample displays its current Android package and signing-certificate SHA-256 so no `keytool` command is required.

In Harnex open **Apps -> New app connection** and use:

| Field | Value |
| --- | --- |
| Display name | `Hello Harnex` |
| Harnex app ID | `hello-harnex` |
| Android package | `io.github.daniele21.harnex.hello` |
| Signer SHA-256 | copy it from the sample |
| Use case | `Generic text generation` |
| Initial preset | `Quality` |

Harnex accepts the displayed colon-separated fingerprint and normalizes it to the canonical 64-character SHA-256 value. Tap **Create & enable connection**. The button enters a visible saving state and then shows either **Connection ready** or an actionable error; creation must never fail silently.

Harnex remains the authority: the Maven dependency and Binder connection do not grant access by themselves. At runtime Harnex derives the caller from Android Binder identity and applies the persisted package/signer/use-case authorization policy.

## 4. Connect and run

Back in Hello Harnex:

1. tap **Connect to Harnex**;
2. wait for `CONNECTED`;
3. keep or edit the example prompt;
4. tap **Run on device**.

The default prompt is:

```text
Explain in two concise sentences why on-device AI can improve privacy.
```

A successful response is normal text streamed back through the Consumer SDK. Hello Harnex sends the prompt unchanged as `ConsumerGenerationInput.Text` and requests `ConsumerOutputConstraint.Text`.

The sample also displays public TTFT, total latency, output-token count and decode throughput when available.

## The generic use case is deliberately bounded

`generic-text-generation` is a host-owned onboarding/integration capability, not a general-purpose chatbot surface. Its current contract is:

- stateless session;
- text input and text output only;
- reasoning not exposed;
- one input message per session;
- bounded input and context;
- Harnex-owned model resolution and `Quality` preset;
- no cloud fallback.

Consumer apps still own their product workflow. If a product needs structured output, domain-specific instructions, different limits or other guarantees, those semantics belong in a deliberate Harnex use case rather than being smuggled through the generic sample.

## Read the integration in three files

Start in this order:

1. [`HelloHarnexRuntime.kt`](app/src/main/java/io/github/daniele21/harnex/hello/HelloHarnexRuntime.kt) — the tiny app-owned seam around the public SDK;
2. [`HelloHarnexClient.kt`](app/src/main/java/io/github/daniele21/harnex/hello/HelloHarnexClient.kt) — the complete supported lifecycle and cleanup boundary;
3. [`HelloHarnexViewModel.kt`](app/src/main/java/io/github/daniele21/harnex/hello/HelloHarnexViewModel.kt) — product-visible state that survives Activity recreation.

`MainActivity.kt` is intentionally only a small Android UI shell.

The client lifecycle is:

```text
create exact host client
  -> connect
  -> discover assigned use case + published preset
  -> activate the host-owned capability
  -> prepare exact execution
  -> create session
  -> generate
  -> terminal event
  -> close session + deactivate
  -> close client when the lifecycle owner is cleared
```

Important failure classes are translated into product-actionable states such as authorization required, configuration required, model not ready, connection unavailable, cancellation and runtime failure. The underlying SDK error remains part of the diagnostic detail; the UI does not need to parse raw Binder failures.

## Validation

Run the sample's focused checks with:

```bash
./gradlew -p samples/hello-harnex :app:testDebugUnitTest :app:assembleDebug
```

The repository publication gate goes further:

```bash
bash scripts/verify-consumer-sdk-publication.sh
```

It publishes run-owned Consumer SDK artifacts, builds both external Consumer projects from Maven coordinates, runs the Hello Harnex lifecycle tests and rejects source/composite/project coupling.

The focused unit tests cover the application-owned lifecycle boundary, including the exact generic TEXT request, concurrent-run rejection, terminal cleanup, close during active generation and actionable authorization failure mapping.

## Privacy and lifecycle

- inference stays on the device through Harnex;
- the sample does not write prompt or generated output to files, logs or diagnostics;
- caller-declared package identity is not used as the trust anchor;
- cancellation uses the public generation handle;
- sessions and activations are released on terminal paths;
- closing the ViewModel cancels active work, releases application-owned leases and closes the Consumer client exactly once;
- Harnex owns model identity, selection, residency and runtime policy.

For work that must intentionally outlive a transient UI/connection observer, do **not** keep an Activity-owned ordinary generation alive. Use the SDK's durable logical-job API and follow its recovery semantics instead.

For the full Consumer SDK contract, see [`../../docs/shared-runtime/consumer-android-sdk.md`](../../docs/shared-runtime/consumer-android-sdk.md).
