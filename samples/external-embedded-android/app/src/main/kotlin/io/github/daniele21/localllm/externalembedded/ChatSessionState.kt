package io.github.daniele21.localllm.externalembedded

import java.util.concurrent.atomic.AtomicReference

/**
 * One app-owned phase gate. Whichever transition wins first decides whether
 * a completed answer may enter the conversation or Stop discards it.
 */
internal class ChatSessionState {
    private val phase = AtomicReference(ChatPhase.NEEDS_MODEL)

    fun current(): ChatPhase = phase.get()

    fun beginPreparation(): Boolean = phase.compareAndSet(ChatPhase.NEEDS_MODEL, ChatPhase.PREPARING)

    fun completePreparation(): Boolean = phase.compareAndSet(ChatPhase.PREPARING, ChatPhase.READY)

    fun beginGeneration(): Boolean = phase.compareAndSet(ChatPhase.READY, ChatPhase.GENERATING)

    fun requestStop(): Boolean {
        while (true) {
            val current = phase.get()
            if (current != ChatPhase.PREPARING && current != ChatPhase.GENERATING) return false
            if (phase.compareAndSet(current, ChatPhase.STOPPING)) return true
        }
    }

    /** Only a running, non-cancelled request may commit its answer. */
    fun acceptCompletion(): Boolean = phase.compareAndSet(ChatPhase.GENERATING, ChatPhase.READY)

    /** Called by the serial worker after a failed or cancelled request finishes. */
    fun settleGeneration(): ChatPhase {
        while (true) {
            val current = phase.get()
            if (current != ChatPhase.GENERATING && current != ChatPhase.STOPPING) return current
            if (phase.compareAndSet(current, ChatPhase.READY)) return ChatPhase.READY
        }
    }

    fun requirePreparation() {
        phase.set(ChatPhase.NEEDS_MODEL)
    }
}
