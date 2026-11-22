# Embedded Whisper Android

Opt-in Maven artifact `io.github.daniele21.localllm:embedded-whisper-android`, staged with the embedded SDK version. ARM64 only. The independent Android sample consumes it from Maven and exercises the same SDK as external applications.

Run `EmbeddedWhisper.prepare` on a worker after user consent. The SDK verifies and installs the pinned Whisper Small multilingual Q5_1 model in no-backup app storage. The consumer declares Internet permission for downloads and owns microphone permission, capture, consent and retention. Call `transcribeItalian` on a worker with mono signed PCM16 at 16 kHz (0.25–30 seconds). Use a fresh `WhisperCancellation` per operation and call `cancel()` when the owning feature closes. Cancellation is cooperative and network reads are bounded by timeouts. Prepare explicitly before offline transcription. Never log or retain audio or transcripts in the SDK.

Acceptance requires the existing external Maven consumer gate, native library and symbol validation, then representative ARM64 measurements and offline, cancellation, and memory contention checks with llama.cpp loaded. This candidate does not change Property or publish a release.
