# Embedded Android voice input — Whisper Small Q5_1

Status: integrated development feature (sample-only) · Product: PRODUCT_FEATURE · Updated: 2026-11-20
Owner: `samples/external-embedded-android`. Android automation is qualified; representative-device performance, interruption and resource evidence remain pending for release, and the sample is not a public SDK.

## User job and smallest supported experience

An Android user wants to speak a short prompt and review its locally transcribed text before asking the existing Qwen3.5 chat. The independent Maven-only embedded sample demonstrates offline dictation; Harnex itself remains a governed text-generation runtime, **not** a chatbot, ASR product, or audio service.

Critical journey: open chat → prepare Harnex LLM separately → tap Microphone → grant Android `RECORD_AUDIO` permission → record up to **30 seconds** of mono speech → tap Finish → transcribe entirely on device → inspect/correct the composer → explicitly tap Send → existing `EmbeddedChatController.send(text)` → streamed Qwen3.5 answer. The microphone must be off during inference. The text composer and chat history stay app-owned.

First release defaults: Whisper **multilingual small Q5_1**, Italian (`language=it`, `translate=false`), CPU only, short push-to-record/finish rather than live partial ASR. Separate model preparation is permitted for the first download; no silent network access during speech inference. Transcription never auto-sends. Normal transcripts and raw audio are not logged or persisted. Empty/silent/failed transcripts are not sent or inserted. Audio is discarded on completion/cancellation.

**Non-goals:** continuous listening, background recording or transcription, Android keyboard/service integration, arbitrary Whisper models, VAD/diarization/voice activity streaming, GPU/Vulkan, cloud fallback, persistent recordings, a voice output/TTS feature, an audio API in Harnex SDK, automatic prompt cleanup with an LLM.

## Product risks and decisions

| Risk | Assessment / inexpensive evidence |
| --- | --- |
| VALUE | Low–medium; realistic short dictation must avoid manual typing |
| USABILITY | Medium; progress, permission denial, transcription error and correction before Send must remain obvious |
| FEASIBILITY | **High**; two independent ggml JNI stacks, cancellation, memory/thermal competition, Android OEM microphone behavior |
| VIABILITY | Medium; pin and maintain a second native implementation and asset; keep it in the sample until repeated consumer demand justifies an SDK |

**Decision: NARROW_SCOPE** to the independent embedded-app sample, with an exact model/runtime artifact, and measure on ARM64 before asserting feature quality. Further Harnex SDK publication would be PRODUCT_STRATEGIC and require a separate product decision.

## Canonical boundaries

```text
Android SpeechInputController (sample-only, exclusive owner of microphone + STT lifecycle)
  -> AudioRecord (permission-gated; 16 kHz mono PCM16, bounded memory, no files)
  -> WhisperNative (sample-only Kotlin JNI adapter, cancellable synchronous call)
  -> libharnex_whisper_stt.so (isolated whisper.cpp + statically linked ggml, hidden symbols)
  -> text only -> EmbeddedChatScreen.prompt (editable)
  -> user's explicit Send -> EmbeddedChatController.send(text)
  -> existing Harnex embedded-android Maven dependency
  -> Qwen3.5 / llama.cpp (unchanged)
```

`EmbeddedChatController` continues owning chat, history, generation, and its own session. No changes to `core/*`, `backends/llama-cpp`, `integrations/android-embedded-sdk`, Binder, public contracts, or Harnex GGUF catalog.

The standalone sample must continue resolving Harnex **only** through its staged Maven artifact. A separate **exact-commit** whisper.cpp source dependency is allowed only to build the sample's JNI library; never use a floating branch, source-project dependency on Harnex, or duplicate a public SDK.

## Model contract

- File: `ggml-small-q5_1.bin`, Whisper **Small multilingual Q5_1**, GGML (not Harnex's GGUF format).
- Canonical upstream artifact: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin`, approximately 190 MB, **190085487 bytes**.
- SHA-256: `ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb`.
- Pin `whisper.cpp` at `9386f239401074690479731c1e41683fbbeac557` (v1.8.4). Requalify the exact pin before any update.
- Store under sample-private files, with a distinct STT directory. Only expose READY after an exact-size/SHA-256 check on temporary bytes and atomic promotion. Failed, cancelled, incomplete, or corrupted downloads must never be used as models. Model transfer may need network; all recording, transcription, and Qwen inference run offline after provisioning.
- Keep all Harnex-owned GGUF download/install and model policy unchanged.

## Lifecycle / concurrency / privacy invariants

1. Android `RECORD_AUDIO` permission is requested as a visible user action. Denied, revoked, unavailable mic or AudioRecord failure is recoverable; no background microphone.
2. `SpeechInputController` owns at most one active capture/transcription; a generation already running disables voice capture. During recording/transcription, Send and New chat are disabled; previously typed drafts are preserved unless the user accepts the transcript.
3. Capture and inference use bounded worker threads, never the main thread. PCM is capped at 30 seconds; the recorder stops first, and only then does Whisper infer. No file, database, analytics, telemetry or content logging for audio/transcripts.
4. Cancel is observable from JNI via `whisper_full_params.abort_callback`; cancelling a coroutine alone is insufficient. A native context is not freed while `whisper_full` is running. Stop/cancel/activity destroy invalidates the current operation so late callbacks cannot change the composer. Release calls are serialized after the native call terminates.
5. Native `ggml` used by Whisper is linked privately into `libharnex_whisper_stt.so`, with hidden/export-limited symbols; never overwrite or load generic shared `libggml*.so` from the Qwen backend. Inspect ELF symbols and dependencies and load **both** engines on ARM64 before claiming collision-free coexistence.
6. CPU-only first; thread count conservative and measured. Do not assume two concurrently resident models are safe. Measure PSS, peak RSS, recovery under memory pressure, transcription latency/RTF, cold versus warm model load, and temperature on physical devices before proposing automatic LLM/ASR residency policy.
7. No assumption that a successfully built APK or x86 emulator UI test proves production JNI inference. Microphone accessibility labels and permission/error feedback must be tested on Android.

## Reference implementations (design references, not imported code)

- [localVoice](https://github.com/lighteningAB/localVoice): exactly Small Q5_1 + Qwen on Android, separate `whisper_jni.cpp` and LLM module; its size-only model checks and mandatory `armv8.2-a` build flag are **not** suitable as Harnex-wide defaults.
- [WhisperShare](https://github.com/DaniilBabanin/WhisperShare): exclusive native context ownership, atomic abort, SHA-256 model identity, recoverable streaming downloads.
- [WhisperNote](https://github.com/nazmos-sakib/WhisperNote): exact-size+SHA-256 download promotion, native optimized builds and physical Pixel 6a performance evidence. Reported Small Q5_1 ~33 seconds on an 11-second English clip there: **do not promise real-time speech** without device evidence.
- [whisper.cpp Android reference](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android): upstream API, `AudioRecord` and JNI reference; don't copy demo-specific default English or unbounded boxed PCM buffering.
- [VoiceSkip](https://github.com/tguillem/VoiceSkip): later reference for VAD, streaming and Vulkan. GPL implementation is not to be copied into this MIT-compatible sample.

## Work sequence and acceptance

| Step | Implementation/proof | State |
| --- | --- | --- |
| STT-00 | Pin and isolate whisper.cpp/ggml JNI, verify ARM64 APK packaging and exports | Implemented; automated PASS |
| STT-10 | Bounded Italian PCM transcription, native abort callback, SHA-256 verified model provisioning | Implemented; real inference coverage remains limited |
| STT-20 | Foreground AudioRecord, permission, cleanup, 30-second cap | Implemented; representative OEM/error-path proof pending |
| STT-30 | Mic controls, editable draft, explicit Send to Qwen | Implemented; synthetic emulator UI PASS; user reports real-device basic flow working |
| STT-40 | FULL CI, Maven-only APK and UI evidence; representative-device latency, resource, cancellation and correctness matrix | Automated PASS; representative-device qualification pending |

**Automated acceptance:** JVM tests for state transitions and stale-result suppression, malformed/short audio, exact file identity and interrupted download; ARM64 JNI in APK, native ABI/linkage/SONAME/symbol verification, both engine libraries packaged, existing Maven-only sample and Harnex Android tests green. Any sample Gradle/JNI/CI changes require the repo-selected stronger gates, not test skips. For material UI journey use the repository's E2E media evidence rules.

**Physical acceptance (the user reports the basic flow works, but full device qualification is pending):** install exact APK on representative ARM64, provision Small Q5 and Qwen GGUF, transcribe 3/10/30-second Italian utterances in airplane mode, edit/send/receive answer, cancel during inference, close/reopen, denied/revoked mic, pressure/thermal and 10-turn stress without late callbacks or memory leaks. Record audio-duration, model load, STT infer time, RTF, PSS/peak memory, real text error (WER/CER); separate device facts from provisional thresholds. Never label UI emulation as native STT proof.

**Outcome:** input voice can reliably become editable text and be sent to existing local Qwen without cloud or Harnex contract changes.
**Product-impact question:** can real users dictate with acceptable accuracy and latency versus typing? Answer from representative, privacy-safe qualitative tests; no prompt telemetry.

## Running / distribution

In development, use the existing `Embedded SDK Maven staging` workflow. It produces a debug-only smoke APK and is not a Harnex Host release or commercial SDK. First model preparation requires a network transfer (~190 MB) and sufficient storage. Record ARM64 qualification only against the exact APK, model digest, whisper.cpp commit and Harnex runtime commit.
