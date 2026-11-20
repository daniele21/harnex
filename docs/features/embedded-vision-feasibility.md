# Embedded Qwen3.5 2B image-question feasibility spike

Status: experimental Android implementation branch; not integrated product support
Updated: 2026-11-20
Owners: Harnex backend for future multimodal execution; `samples/external-embedded-android` for chat UX only.

## Product intent and decision

- **Product depth:** PRODUCT_FEATURE discovery, not product activation.
- **Consumer/job:** the independent embedded Android chat sample should eventually let a user select one local photo and ask a question about it using the already selected Qwen3.5 2B, with no cloud upload or second LLM runtime.
- **Desired outcome:** image+text inference works with the exact reviewed 2B model and the pinned native backend without regressing text chat, dictation, streaming or cancellation.
- **Decision now:** NARROW_SCOPE to a falsifiable Linux-host feasibility probe. Do not ship an attachment picker, add an image to the SDK contract, or change a production model claim before runtime evidence exists.
- **Non-goals:** OCR pipeline, camera capture, multiple images, video, voice changes, arbitrary model/projector import, Binder multimodal exposure, cloud inference or a new inference engine.
- **Risks:** VALUE medium (the actual value of photo questions is unmeasured); USABILITY medium (attachment/error behavior not yet designed); FEASIBILITY high (model/projector compatibility, native image encoder, context, memory and cancellation); VIABILITY medium (larger model download and supported-device matrix). The spike cheaply addresses one feasibility uncertainty.

This expands the current `docs/qwen35/target.md` text-only *proposal* only if the follow-on product decision is supported by evidence. That target and the public embedded SDK remain unchanged by this spike.

## Exact inputs and ownership

| Artifact | Immutable identity | Source |
| --- | --- | --- |
| Qwen3.5 2B Q4_K_M (text model already used by sample) | SHA-256 `aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223`, 1,280,835,840 bytes | `unsloth/Qwen3.5-2B-GGUF` at `802854bfd388ed92748de119df31327962811548` |
| Vision projector F16 | SHA-256 `7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7`; approximately 668 MB | `unsloth/Qwen3.5-2B-GGUF` at `1c466474d208da1a7c4b8cb87ebcdac78f160e34` |
| llama.cpp with libmtmd | commit `aedb2a5e9ca3d4064148bbb919e0ddc0c1b70ab3` (tag `b9637`) | current `third_party/llama.cpp` pin |

The 2B model identity is already owned by the curated catalog. The projector digest comes from the exact Hugging Face LFS/Xet pointer at the immutable revision above. Pair compatibility is a **hypothesis**, not certified by shared repository naming or metadata alone. Neither artifact is committed to the repository.

## Current blocking boundaries

1. `GenerationInput` and `ConsumerGenerationInput` support text/messages, not image data.
2. `BackendPromptPlan` and `BackendGenerationRequest` pass formatted text, not visual embeddings or image chunks.
3. The production Android JNI CMake target builds llama.cpp text generation with `LLAMA_BUILD_TOOLS=OFF`; the pinned upstream `tools/mtmd` source exists but is not linked into the Android backend.
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
- **Android arm64 CMake/JNI/ELF packaging:** NOT TESTED. Must compile `libmtmd` via the existing backend owner and verify a single compatible native stack.
- **Android Qwen+vision real-GGUF inference on representative phone:** NOT TESTED. Required before claiming on-device support.
- **Memory/lifecycle/thermal:** NOT TESTED. Measure warm/cold model+projector residency, peak RSS/PSS, first-answer time, 10 sequential image questions, cancellation during image encoding/generation, backgrounding and recovery. Fail without leaking transient image buffers.
- **Supported API and UX:** IMPLEMENTED EXPERIMENTALLY IN BRANCH. Requires Maven-only APK build, UI E2E and physical native qualification before integration; Shared Binder media remains intentionally unsupported. After the above succeeds, shape a small image-aware neutral request at the canonical contract/runtime owner (with compatibility and direct-consumer evidence), then add one-photo selection and preview in the independent chat sample. Keep voice and text-only paths unchanged.

**Acceptance**: exact image+text request produces image-grounded answers locally, typed errors/cancellation and no content logs, while existing text chat remains equivalent. **Outcome**: an Android app can ask about a selected picture without implementing a second LLM engine. **Product impact**: representative user image-question task success and perceived latency, measured without content telemetry.

The current experiment is an ITERATION / STRONG-risk native feasibility step. Native Android automated qualification is REMOTE_AUTOMATED when toolchain unavailable locally; true phone memory/thermal and actual-model behavior are REAL_ENVIRONMENT. No merge/release readiness claim follows from the host smoke.

## Android prototype scope (unmerged)

Experimental branch `agent/qwen35-2b-vision-android-apk` extends the canonical
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

**Build and physical acceptance pending:** exact Maven-only APK build, JNI ELF
packaging and native load, genuine image questions on-device with red/blue and
real photographs, stream/Stop/background/retry, PSS/RSS/thermal, representative
a11y and UI full-media journey. Existing chat and Whisper must not regress.
