# Standalone embedded Android smoke fixture

This is a **separate Gradle build**, not an Harnex module or the commercial chat app.
It consumes only a versioned Maven coordinate:
`io.github.daniele21.localllm:embedded-android:<version>`.

## Automated proof

From the Harnex checkout (Android SDK/NDK and pinned native sources available):

```sh
EMBEDDED_SDK_VERSION=0.1.0-SNAPSHOT bash scripts/verify-embedded-sdk-publication.sh
```

This publishes release AARs to `build/embedded-sdk-repository` and assembles the
independent sample APK using **only** that staged Maven repository. The script
checks the `arm64-v8a` JNI library in the published backend AAR and final APK.
It must not use `project(...)`, `includeBuild(...)`, or a source checkout as a
dependency. An AAR/compile success is **not** evidence of real GGUF generation.

## Device smoke (requires representative physical ARM64 hardware)

Install `app/build/outputs/apk/debug/app-debug.apk` from this sample after
running the staging script. On an Android ARM64 device with enough RAM/storage,
tap **Download / Install / Generate**. This action:

1. opens the Harnex in-process facade with the reviewed Qwen3.5 2B Q4_K_M model;
2. securely downloads its catalog-pinned GGUF into private storage (unless already installed);
3. verifies the transfer and metadata, installs it and verifies the installed digest;
4. prepares the native runtime; creates a technical **stateless** session;
5. streams a request with `USER, ASSISTANT, USER` messages, then a second
   request with **only one** `USER` message; both requests supply their own
   entire selected input, with no hidden conversation manager;
6. closes the session and runtime.

The app asks for `INTERNET` only because model provisioning needs HTTPS. The
SDK has no Host/Binder or cloud-inference fallback. Inputs and outputs stay in
the app's visible screen and are not sent to ordinary logs. The demo has a
Cancel button that cancels transfer or inference.

Retain an exact APK/build SHA, artifact SHA-256, device model, Android version,
ABI, model digest, native outputs, cancellation/teardown evidence and memory
results before claiming physical runtime readiness. A CI emulator build does
not qualify physical JNI/GGUF behavior, RAM, thermal or OEM support.

**Status:** integration candidate only. The sample is not a release nor
commercial-app readiness evidence until the automatic and physical gates pass.
