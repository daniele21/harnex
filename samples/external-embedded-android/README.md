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
The APK includes a **minimal, interactive local chat demo**, but no GGUF model
or Harnex Host. It is debug-signed and is neither a production chat app nor a
published SDK/Play release.

## Chat design references and intentionally narrow adoption

The consumer-side architecture draws on **RikkaHub**'s single authoritative
in-memory `ConversationSession` / `ChatService` contract and **PocketPal AI**'s
`GenerationSlot`/abort lease, separate agent runner, and coalesced streaming
UI updates. **ChatterUI** is a mobile reference for keeping chat interactions
separate from local inference configuration:

- RikkaHub: https://github.com/rikkahub/rikkahub/blob/master/app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt
- PocketPal: https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/store/generationLease.ts
- PocketPal: https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/services/agent/AgentRunner.ts
- ChatterUI: https://github.com/Vali-98/ChatterUI

These are architectural references, not copied implementations. This demo
adapts the *ownership* and *cancellation/streaming* patterns in native Kotlin:
one app-side `EmbeddedChatController` owns a serial worker and the temporary
transcript, `EmbeddedChatGeneration` translates Harnex token/terminal events,
and `EmbeddedChatScreen` renders the UI. **Harnex Embedded SDK is the only
inference engine.** Unlike the mature references, this proof deliberately
omits persistent multi-chat history, branching, agents/tools, remote
providers, automatic replay and cross-device synchronization.

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
deltas into a conversational message surface. **Stop** requests cancellation of the in-flight
download/generation; the chat is reusable only after the engine acknowledges completion.
If generation ignores Stop for eight seconds, the demo marks the runtime for reinitialization
rather than silently treating the old session as ready. A normal unanswered generation has
a separate 120-second limit. **New chat** erases only the app's temporary conversation,
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

**Automation and evidence:** Maven-only external consumer unit tests cover
bounded history and Stop/Completed races. The compiled APK is checked for ARM64
JNI and initial setup/composer state on an Android API 35 x86_64 emulator.
A **debug-only UI journey fixture**, shell-restricted and absent from release
variants, drives the actual chat screen through synthetic streaming, Stop,
draft recovery, New chat and a completed answer; the emulator retains
accessibility assertions, screenshot and video. This fixture does not call
Harnex inference and is explicitly **UI-only**. Physical ARM64 must still
qualify real GGUF streaming, cancellation, relaunch, multi-turn and resources
before SDK handoff/release.

The activity does not intentionally write prompts/responses to Logcat or disk.
Android may still include text in standard system accessibility or memory
surfaces while the app is in use. UI state is reset upon Activity recreation;
the installed model remains in its app-private directory. Debug APK signing
may differ between CI runs: uninstall the previous smoke demo if Android
rejects an update signature. The real Harnex Host app is independent.

## Optional local voice input (development)

The independent Android chat sample can also dictate text with **Whisper Small
multilingual Q5_1**. This is **not** part of the public Harnex Embedded SDK:
the microphone, model and `whisper.cpp` JNI adapter belong to this app. The
voice path never calls a cloud recognition service.

1. Prepare the existing Qwen3.5 model as usual.
2. Tap **Prepare Whisper Small Q5** (first use downloads ~190 MB over HTTPS;
   exact size and SHA-256 are verified before the private GGML file becomes ready).
3. Tap **Record voice**, grant Android microphone permission, and speak Italian
   for up to 30 seconds. **Finish recording** stops capture and starts local
   recognition. **Cancel audio** prevents a late transcript from appearing.
4. Edit the resulting text in the composer and press the existing **Send**
   button. The chat still sends only explicit text to Harnex Qwen3.5.

The microphone and the transcription are never active in the background. Audio
lives only in bounded memory and is discarded when processing finishes; no
recordings/transcripts enter normal logs or persistent app storage. Offline
recognition works after the model has been downloaded and verified. Transcribing
and Qwen text generation do not overlap in the UI.

Whisper is compiled from **whisper.cpp v1.8.4 at exact SHA
`9386f239401074690479731c1e41683fbbeac557`**, statically linking its own
ggml and hiding those symbols from Harnex's independent llama.cpp native AAR.
Only `arm64-v8a` is targeted. A successful build/emulator UI run does **not**
qualify real Italian STT, concurrency, RAM or temperature on physical phones.

See [Voice Input feature/architecture](../../docs/features/embedded-voice-input.md)
for model identity, architectural boundaries, cancellation behavior, acceptance
gates and representative-device requirements.

## Experimental single-photo questions (Android ARM64)

This branch adds **Prepare vision**, **Choose photo**, a transient photo preview, and
single-image questions to the independent Maven-only embedded chat sample. These controls
are *experimental*: native multimodal Android compatibility, memory and cancellation
are not qualified merely by a green Linux-host spike or APK packaging checks.

1. Tap **Prepare local model** to verify/download the existing Qwen3.5 2B Q4_K_M GGUF.
2. Tap **Prepare vision** to download the matched F16 projector (~668 MB). The fixed
   SHA-256 (`7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7`)
   and exact size (668,227,264 bytes) are verified via Harnex SecureModelDownloader.
3. Tap **Choose photo** and pick a single photo using the platform Photo Picker or
   its API 26+ content fallback. The photo is decoded off the UI thread, downsampled
   to at most 512 pixels per side, encoded as JPEG and kept only in memory.
4. Enter a question and press **Send**. Harnex routes one image question through
   the existing RuntimeOrchestrator, backend instance, model and native cancellation
   registry using the pinned libmtmd projector. You can press **Stop**; it takes effect
   cooperatively and can lag during a blocking native visual encode.
5. **Remove** drops the selected photo without sending. Text-only questions and
   Whisper voice input continue to use their original paths.

Image questions deliberately do not join the app's text-only history: later text
turns do not implicitly retain the image. Normal content logging, persistent
image files, cloud inference, arbitrary projector URLs, multi-image inputs and
Binder multimedia serialization remain disabled. The projector is stored only
inside this app's private no-backup directory, not bundled with the APK.

**Qualification status:** Linux host GGUF+projector smoke PASS on exact pinned
llama.cpp; Android ARM64 build and runtime evidence must come from the exact
Android CI run and representative device. A built APK alone does not establish
correct native image inference, peak memory/thermal behavior or image cancellation.
