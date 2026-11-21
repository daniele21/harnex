# Embedded Qwen3.5 2B image-question feasibility and Android integration

Status: experimental embedded-Android capability for `dev` integration; not release-qualified or general model support
Updated: 2026-11-21
Owners: Harnex backend for future multimodal execution; `samples/external-embedded-android` for chat UX only.

## Product intent and decision

- **Product depth:** PRODUCT_FEATURE discovery, not product activation.
- **Consumer/job:** the independent embedded Android chat sample should eventually let a user select one local photo and ask a question about it using the already selected Qwen3.5 2B, with no cloud upload or second LLM runtime.
- **Desired outcome:** image+text inference works with the exact reviewed 2B model and the pinned native backend without regressing text chat, dictation, streaming or cancellation.
- **Initial spike decision:** NARROW_SCOPE to a falsifiable Linux-host feasibility probe before committing to the Android implementation. Subsequent host success, a Maven-only Android build, green UI/SDK gates and a user-reported on-device image-question smoke justify experimental `dev` integration, not a general production model claim.
- **Non-goals:** OCR pipeline, camera capture, multiple images, video, voice changes, arbitrary model/projector import, Binder multimodal exposure, cloud inference or a new inference engine.
- **Risks:** VALUE medium (representative task success unmeasured); USABILITY medium (preview and error behavior implemented but not representative-user qualified); FEASIBILITY high (native image encoding, cancellation, peak memory and thermal stability beyond a single phone smoke); VIABILITY medium (additional projector download and supported-device coverage). Automated checks and one physical smoke reduce, but do not eliminate, these risks.

The experimental implementation introduces a bounded `GenerationInput.ImageQuestion` and an embedded-only projector capability. It does **not** extend Shared Host/Binder multimedia support or change the curated production model-support envelope in `docs/qwen35/target.md`.

## Exact inputs and ownership

| Artifact | Immutable identity | Source |
| --- | --- | --- |
| Qwen3.5 2B Q4_K_M (text model already used by sample) | SHA-256 `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223`, 1,280,835,840 bytes | `unsloth/Qwen3.5-2B-GGUF` at `802854bfd388ed92748de119df31327962811548` |
| Vision projector F16 | SHA-256 `7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7`; approximately 668 MB | `unsloth/Qwen3.5-2B-GGUF` at `1c466474d208da1a7c4b8cb87ebcdac78f160e34` |
| llama.cpp with libmtmd | commit `aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3` (tag `b9637`) | current `third_party/llama.cpp` pin |

The 2B model identity is already owned by the curated catalog. The projector digest comes from the exact Hugging Face LFS/Xet pointer at the immutable revision above. Pair compatibility is a **hypothesis**, not certified by shared repository naming or metadata alone. Neither artifact is committed to the repository.

## Architectural boundaries addressed in the experimental implementation

1. `GenerationInput.ImageQuestion` carries bounded image bytes locally; `ConsumerGenerationInput`/Shared Binder deliberately remain text-only and reject multimedia.
2. Prompt planning and the existing backend pass image bytes through the single native generation pipeline, without a second LLM runtime.
3. Android JNI links pinned upstream `libmtmd` in the existing `llama.cpp` backend; packaging and host functional tests pass, but physical-device reliability is not established by compilation.
4. The standalone Maven-only embedded sample owns chat and dictation. It must not build its own duplicate llama.cpp inference runtime, bypass SDK packaging or silently pipe images to cloud.
5. Image selection, transient staging, encoded image lifetime and logging policy must remain app/UI and runtime responsibilities in their respective layers; private images must not enter diagnostics or normal telemetry.

## Automated experiment

The dedicated `.github/workflows/qwen35-2b-vision-spike.yml` runs **only on GitHub-hosted Ubuntu**, on pull requests changing this spike/workflow, or on explicit workflow dispatch. It does not target a self-hosted runner, modify Android build settings, publish an APK or mutate Harnex app/runtime code.

Steps:

1. Check out the exact Harnex source and recursive llama.cpp submodule; verify the pinned upstream commit.
2. Compile upstream `llama-mtmd-cli` with host CPU only; no production JNI or model store changes.
3. Download the exact model/projector from immutable revisions, verify SHA-256 of both and model byte size. Fail closed on HTTP, integrity or build errors.
4. Generate two tiny synthetic PNGs (solid red, solid blue), with the same neutral question for both and different expected answers. A question-only text model cannot infer both colors from the identical prompt.
5. Ask each color question through the upstream `libmtmd` path, requiring successful execution and the expected answer. Retain only SHA identities, verdict and the explicit `LINUX_HOST_ONLY` scope; inputs and process logs remain ephemeral.
6. Remove downloaded model/projector bytes and test images on all completion paths. No personal images or prompts are included.

**Automated result:** Linux-host PASS for both synthetic fixtures at commit \`a4c75d6414609eb2b6ec487350b0915681a1cb2d\`; Android qualification remains separate. Compilation success alone is not an inference PASS. A wrong color, crash, timeout, missing projector or hash mismatch is a real spike failure requiring diagnosis, not an excuse to bypass the test.

## Evidence and follow-on acceptance

- **Linux-host model/projector functional compatibility:** PASS on exact pinned input hashes and reference question fixtures (GitHub Actions run 44811518006). This result establishes this narrow host claim only.
- **Android arm64 CMake/JNI/ELF packaging:** PASS on the exact candidate through FULL validation and independently assembled Maven-only debug APK (GitHub Actions run `44896111496`, source `a07bb965`).
- **Android Qwen+vision real-GGUF inference on a phone:** ONE USER-REPORTED SMOKE SUCCESS. A Samsung Android screenshot shows a single photo question completing with local-model output. This is useful real-device evidence, not controlled model correctness, broad device compatibility or repeatability proof.
- **Memory/lifecycle/thermal:** NOT QUALIFIED. The screenshot reports approximately 1,480 MB process PSS and 22 seconds wall time for one answer; this is neither peak residency nor a benchmark. Still measure warm/cold residency, peak RSS/PSS, sequential image questions, native cancellation, background/recovery and thermals without retaining image buffers.
- **Supported API and UX:** EXPERIMENTAL DEV INTEGRATION. Bounded image-question request, transient picker/preview, voice and text coexist in the sample. Maven-only packaging, full automated gates and a synthetic UI full-media journey passed. Shared Binder media remains unsupported; physical cancellation/resource/OEM acceptance remains deferred to release/consumer qualification.

**Acceptance**: exact image+text request produces image-grounded answers locally, typed errors/cancellation and no content logs, while existing text chat remains equivalent. **Outcome**: an Android app can ask about a selected picture without implementing a second LLM engine. **Product impact**: representative user image-question task success and perceived latency, measured without content telemetry.

Integration classification: PRODUCT_FEATURE / INTEGRATION / FULL / REMOTE_AUTOMATED for Android and native gates. These automated gates pass on the cited candidate. Representative phone memory/thermal, sustained multimodal correctness, interruptions and OEM behavior remain REAL_ENVIRONMENT; one smoke report does not make the capability release-ready or prove product impact.

## Android implementation scope (experimental `dev`)

The integrated experimental implementation extends the canonical
backend using `libmtmd` from the same llama.cpp pin, and retains Harnex
`RuntimeOrchestrator` scheduling, a single backend model/context, text streaming
and its cancellation registry. The embedded SDK alone owns the fixed
SHA-256-verified F16 projector artifact. `GenerationInput.ImageQuestion`
is a bounded byte payload and the Binder mapping explicitly rejects it.

The independent sample now offers one-photo selection, bounded image decoding
and a transient preview with explicit Send and Remove. The photo is not stored
with history. The current vision projector is loaded for a request and released
before decode to reduce simultaneous residency. Image preprocessing can be a
blocking native operation, so responsiveness and cancellation require real
ARM64 proof.

**Integration evidence:** exact Maven-only ARM64 APK, JNI packaging, FULL Android/native
validation, direct consumer checks and synthetic UI full-media journey PASS in run
`44896111496`. One user-reported phone photo question produced an answer.
**Remaining physical qualification:** controlled image correctness across fixtures,
real-photo repetition, stream/Stop during encoding, background/retry, sustained
PSS/RSS/thermal and representative accessibility/OEM tests. Existing chat and
Whisper must not regress.

## Screen-lock-safe model distribution in the experimental sample

The sample now delegates long downloads to a JobScheduler user-initiated job
(Android 14+) or a foreground data-sync service (Android 8–13), with a
notification, explicit Pause, and content-free persisted status independent of
the Activity. The canonical SecureModelDownloader retains digest-bound resumable
partial files; it checks `206 Content-Range` against the known artifact size,
rehashes the accumulated complete file, and only publishes verified SHA-256
content. Bad range responses fall back to a full download, never an unsafe
append. The original embedded `downloadModel` API remains available; the
Android-managed path explicitly opts into `downloadModelResumable`.

CI requirements: downloader fake-HTTP cases (early EOF, rejected/missing range,
process-restart resume, checksum mismatch and explicit cancellation), precise
SDK/consumer API compatibility, Maven-only ARM64 APK packaging, existing chat
and Whisper UI evidence, and Android lint/static gates. Screen locking on an
actual OEM phone, process kill, battery policies, network transitions and
notification behavior remain REAL_ENVIRONMENT evidence. Neither emulator UI
nor build success demonstrates download survival on a locked physical phone.
