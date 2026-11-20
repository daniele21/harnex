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
checks the `arm64-v8a` JNI library in the published backend AAR and final APK. The sample also sets `android.packaging.jniLibs.useLegacyPackaging = true` so Android extracts `.so` files to `applicationInfo.nativeLibraryDir`, which the current llama.cpp backend expects. Apply the same setting in external consumer apps until Harnex supports non-extracted JNI installation.
It must not use `project(...)`, `includeBuild(...)`, or a source checkout as a
dependency. An AAR/compile success is **not** evidence of real GGUF generation.

## Download the smoke APK without building

The [Embedded SDK Maven staging GitHub Actions workflow](https://github.com/daniele21/harnex/actions/workflows/embedded-sdk.yml)
uploads an installable **debug-only** APK when its Maven publication, external
consumer build, and ARM64 JNI packaging checks pass.

1. Open a **successful run on `dev`** and find its **Artifacts** section.
2. Download `harnex-embedded-smoke-apk-<run-id>` (a ZIP archive).
3. Unzip it and install **`harnex-embedded-smoke-arm64.apk`** on an ARM64
   Android device. The ZIP also includes `SHA256SUMS` and
   `build-manifest.json` tying the APK to its source revision and workflow run.
4. Android may ask for permission to install apps from your browser or file
   manager. Use the APK only for this test, not as a production application.

Artifacts remain available for **7 days** and GitHub may require signing in.
The APK does **not** include a GGUF model, a Harnex Host, or a chat app.
It is debug-signed; it must not be represented as a published SDK or a
production/Play release.

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
Cancel button that cancels transfer or inference. The actions remain in a
high-contrast bottom bar while status/output can scroll. The staging workflow
tests a no-native x86_64 Android API 35 emulator canary and asserts both buttons
are visible and clickable, retaining screenshot and accessibility-tree evidence.
This tests UI only; real ARM64/GGUF inference is a separate physical-device gate.

Retain an exact APK/build SHA, artifact SHA-256, device model, Android version,
ABI, model digest, native outputs, cancellation/teardown evidence and memory
results before claiming physical runtime readiness. A CI emulator build does
not qualify physical JNI/GGUF behavior, RAM, thermal or OEM support.

**Status:** integration candidate only. The sample is not a release nor
commercial-app readiness evidence until the automatic and physical gates pass.
