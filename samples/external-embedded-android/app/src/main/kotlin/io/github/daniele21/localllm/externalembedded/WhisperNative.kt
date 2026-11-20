package io.github.daniele21.localllm.externalembedded

import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-local bridge: its statically linked whisper.cpp/ggml is not part of Harnex.
 * Each call owns and releases its native context before returning.
 */
internal object WhisperNative {
    init {
        System.loadLibrary("harnex_whisper_stt")
    }

    external fun transcribe(
        modelPath: String,
        audio: FloatArray,
        cancelled: AtomicBoolean,
    ): String
}
