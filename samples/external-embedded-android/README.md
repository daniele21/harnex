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

## Interactive local chat demo (physical ARM64 phone)

Install the smoke APK from a successful Embedded SDK Maven staging run. The app
is a self-contained, independent Android consumer of the staged
`io.github.daniele21.localllm:embedded-android` AAR: **no Host/Binder**.

The first **Prepare local model** tap opens the embedded runtime on a worker,
downloads the exact reviewed Qwen3.5 2B Q4_K_M GGUF if absent, verifies the
download, installs the artifact in the app's private storage, checks the
installation, prepares the native engine, and opens one **stateless technical
session**. Later app launches reuse the verified GGUF; the large model is not
bundled in the APK.

After **Ready**, type a question and press **Send**. The demo streams answer
deltas into a conversational message surface. **Stop** cancels the in-flight
download/generation, and **New chat** erases only the app's temporary conversation,
not the verified model. Failed/cancelled or incomplete answers are not committed
to subsequent context; after a failure, the draft prompt is restored.

The app, **not Harnex**, owns the chat transcript in memory. Each request
constructs an explicit `GenerationInput.Messages` list containing at most
six previous completed USER/ASSISTANT turns (plus the new USER message) with
a 6,000-character app-selected context budget; each new prompt is limited to
600 characters. No automatic persistence, chat titles, cloud services, model
fallback, content telemetry, or built-in Harnex conversation manager.

The status area shows preparation/download/installation stages; completed
generations show SDK metrics (time to first token, total latency, output
tokens, decoding tokens/sec) and a **process PSS snapshot** in MB. The PSS
snapshot is not peak RSS, thermal qualification, or a benchmark result. Native
execution and cancellation need verification on the actual phone.

**Automation and evidence:** the Maven-only independent sample runs its own
history-selection unit tests, compiles an external APK with the ARM64 JNI
backend, and checks initial setup/composer state on an Android API 35 x86_64
emulator (a no-native UI canary). Those checks do **not** prove local inference
or exercise the enabled composer. A full interactive real-GGUF chat, Stop,
relaunch and multi-turn journey still needs claim-matched **physical ARM64**
evidence before an SDK handoff/release claim.

The activity does not intentionally write prompts/responses to Logcat or disk.
Android may still include text in standard system accessibility or memory
surfaces while the app is in use. UI state is reset upon Activity recreation;
the installed model remains in its app-private directory. Debug APK signing
may differ between CI runs: uninstall the previous smoke demo if Android
rejects an update signature. The real Harnex Host app is independent.
