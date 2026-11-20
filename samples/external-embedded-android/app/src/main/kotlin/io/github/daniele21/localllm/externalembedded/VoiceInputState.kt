package io.github.daniele21.localllm.externalembedded

import java.util.concurrent.atomic.AtomicReference

/** App-local speech lifecycle, independent of Harnex's ChatSessionState. */
enum class VoicePhase {
    NEEDS_MODEL,
    PREPARING,
    READY,
    RECORDING,
    TRANSCRIBING,
    CANCELLING,
}

internal class VoiceInputState {
    private val phase = AtomicReference(VoicePhase.NEEDS_MODEL)

    fun current(): VoicePhase = phase.get()

    fun modelAlreadyReady(): Boolean = phase.compareAndSet(VoicePhase.NEEDS_MODEL, VoicePhase.READY)

    fun beginPreparation(): Boolean = phase.compareAndSet(VoicePhase.NEEDS_MODEL, VoicePhase.PREPARING)

    fun completePreparation(): Boolean = phase.compareAndSet(VoicePhase.PREPARING, VoicePhase.READY)

    fun beginRecording(): Boolean = phase.compareAndSet(VoicePhase.READY, VoicePhase.RECORDING)

    fun finishRecording(): Boolean = phase.compareAndSet(VoicePhase.RECORDING, VoicePhase.TRANSCRIBING)

    fun completeTranscription(): Boolean = phase.compareAndSet(VoicePhase.TRANSCRIBING, VoicePhase.READY)

    fun cancel(): Boolean {
        while (true) {
            val now = phase.get()
            if (now != VoicePhase.PREPARING && now != VoicePhase.RECORDING && now != VoicePhase.TRANSCRIBING) {
                return false
            }
            if (phase.compareAndSet(now, VoicePhase.CANCELLING)) return true
        }
    }

    /** Only the serial voice worker can settle terminal work, never a late JNI callback. */
    fun settle(modelReady: Boolean): VoicePhase {
        val next = if (modelReady) VoicePhase.READY else VoicePhase.NEEDS_MODEL
        phase.set(next)
        return next
    }
}
