package io.github.daniele21.localllm.externalembedded

import android.os.SystemClock
import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class ChatGenerationOutcome(val terminal: GenerationEvent, val streamedAnswer: String, val startedAtMs: Long)

/** One bounded, synchronous worker-thread request. No chat memory or runtime policy is owned here. */
internal class EmbeddedChatGeneration {
    fun execute(
        engine: EmbeddedRuntime,
        session: SessionId,
        messages: List<ConversationMessage>,
        cancelled: AtomicBoolean,
        destroying: AtomicBoolean,
        activeGeneration: AtomicReference<GenerationHandle?>,
        imageBytes: ByteArray?,
        question: String,
        onAnswerDelta: (String) -> Unit,
    ): ChatGenerationOutcome {
        val finished = CountDownLatch(1)
        val terminal = AtomicReference<GenerationEvent?>()
        val stream = StringBuilder()
        val lastRendered = AtomicLong(0L)
        val started = SystemClock.elapsedRealtime()
        val listener = GenerationListener { event ->
            when (event) {
                is GenerationEvent.TextDelta -> {
                    val stillActive = !cancelled.get() && !destroying.get()
                    if (event.contentType == GenerationContentType.ANSWER && stillActive && terminal.get() == null) {
                        val answer = synchronized(stream) {
                            stream.append(event.text)
                            stream.toString()
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastRendered.get() >= 90L) {
                            lastRendered.set(now)
                            onAnswerDelta(answer)
                        }
                    }
                }

                is GenerationEvent.Completed, is GenerationEvent.Failed -> {
                    if (terminal.compareAndSet(null, event)) finished.countDown()
                }

                else -> Unit
            }
        }
        val handle = if (imageBytes == null) {
            engine.generate(session, messages, listener, overrides = GenerationOverrides(maxOutputTokens = 256))
        } else {
            engine.generateImage(session, question, imageBytes, listener, overrides = GenerationOverrides(maxOutputTokens = 256))
        }
        activeGeneration.set(handle)
        try {
            awaitTerminal(finished, handle, cancelled, destroying)
            return ChatGenerationOutcome(
                terminal = checkNotNull(terminal.get()) { "Generation ended without a terminal event" },
                streamedAnswer = synchronized(stream) { stream.toString() },
                startedAtMs = started,
            )
        } finally {
            if (terminal.get() == null) handle.cancel()
            activeGeneration.compareAndSet(handle, null)
        }
    }

    private fun awaitTerminal(finished: CountDownLatch, handle: GenerationHandle, cancelled: AtomicBoolean, destroying: AtomicBoolean) {
        val startedAt = SystemClock.elapsedRealtime()
        var cancellationStartedAt: Long? = null
        while (!finished.await(250, TimeUnit.MILLISECONDS)) {
            val now = SystemClock.elapsedRealtime()
            if (cancelled.get() || destroying.get()) {
                handle.cancel()
                val firstCancel = cancellationStartedAt ?: now.also { cancellationStartedAt = it }
                if (now - firstCancel >= CANCELLATION_TIMEOUT_MS) {
                    throw GenerationTimeout("Cancellation not acknowledged; reinitialize the model")
                }
            } else if (now - startedAt >= GENERATION_TIMEOUT_MS) {
                handle.cancel()
                throw GenerationTimeout()
            }
        }
    }

    private companion object {
        const val GENERATION_TIMEOUT_MS = 120_000L
        const val CANCELLATION_TIMEOUT_MS = 8_000L
    }
}

internal class GenerationTimeout(message: String = "Generation timed out; reinitialize model before retrying") :
    IllegalStateException(message)
