package io.github.daniele21.localllm.embedded.whisper

import android.content.Context
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** One token per operation. Cancellation is cooperative, including native decoding. */
class WhisperCancellation {
    internal val signal = AtomicBoolean(false)
    fun cancel() {
        signal.set(true)
    }

    val isCancelled: Boolean get() = signal.get()

    internal fun check() {
        if (signal.get()) throw CancellationException("Whisper operation cancelled")
    }
}

/**
 * Opt-in offline Whisper Small Q5_1. Call from an app worker.
 * The app owns capture, microphone permission, consent and transcript persistence.
 * Only prepare may use the network. One operation per process at a time.
 */
class EmbeddedWhisper(context: Context) {
    private val models = WhisperModelStore(context.applicationContext)

    fun isReady(): Boolean = operate(WhisperCancellation()) {
        models.verifiedModel() != null
    }

    fun prepare(
        cancellation: WhisperCancellation = WhisperCancellation(),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        operate(cancellation) {
            models.ensureReady(cancellation, onProgress)
        }
    }

    /** Signed mono PCM16 at 16 kHz, 0.25–30 seconds, transcribed in Italian. */
    fun transcribeItalian(audio: ShortArray, cancellation: WhisperCancellation = WhisperCancellation()): String = operate(cancellation) {
        require(audio.size in 4_000..480_000) { "Expected 0.25–30 seconds of mono PCM16 at 16 kHz" }
        val model = checkNotNull(models.verifiedModel()) { "Prepare Whisper before transcribing" }
        cancellation.check()
        val samples = FloatArray(audio.size) { audio[it] / 32768f }
        try {
            WhisperNative.transcribe(model.absolutePath, samples, cancellation.signal)
                .toString(Charsets.UTF_8).trim()
        } catch (failure: IllegalStateException) {
            cancellation.check()
            throw failure
        }
    }

    private inline fun <T> operate(cancellation: WhisperCancellation, block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Call Whisper from a worker" }
        cancellation.check()
        check(active.compareAndSet(false, true)) { "Another Whisper operation is active" }
        try {
            val result = block()
            cancellation.check()
            return result
        } finally {
            active.set(false)
        }
    }

    private companion object {
        val active = AtomicBoolean(false)
    }
}
