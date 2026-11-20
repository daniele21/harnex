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
    ;

    val isBusy: Boolean
        get() = this == RECORDING || this == TRANSCRIBING || this == CANCELLING
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

    /**
     * The serial worker settles an unfinished operation. Once an operation has
     * already published READY, its final cleanup must not affect a newer one.
     */
    fun settleIfUnfinished(completed: Boolean, modelReady: Boolean): VoicePhase? {
        if (completed) return null
        val next = if (modelReady) VoicePhase.READY else VoicePhase.NEEDS_MODEL
        while (true) {
            val previous = phase.get()
            val pending =
                if (modelReady) {
                    previous == VoicePhase.RECORDING ||
                        previous == VoicePhase.TRANSCRIBING ||
                        previous == VoicePhase.CANCELLING
                } else {
                    previous == VoicePhase.PREPARING || previous == VoicePhase.CANCELLING
                }
            if (!pending) return null
            if (phase.compareAndSet(previous, next)) return next
        }
    }
}
