package io.github.daniele21.localllm.embedded.whisper

import java.util.concurrent.atomic.AtomicBoolean

internal object WhisperNative {
    init {
        System.loadLibrary("harnex_whisper_stt")
    }

    external fun transcribe(modelPath: String, audio: FloatArray, cancelled: AtomicBoolean): ByteArray
}
