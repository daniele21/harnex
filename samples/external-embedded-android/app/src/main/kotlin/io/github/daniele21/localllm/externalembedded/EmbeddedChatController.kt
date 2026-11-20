package io.github.daniele21.localllm.externalembedded

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.embedded.EmbeddedConfiguration
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** App-only state. Harnex never owns the conversation, composer or transcript. */
enum class ChatPhase {
    NEEDS_MODEL,
    PREPARING,
    READY,
    GENERATING,
    STOPPING,
}

interface ChatObserver {
    fun onPhase(phase: ChatPhase)

    fun onStatus(status: String)

    fun onDiagnostics(text: String)

    fun onTurnStarted(id: Long, input: String)

    fun onAnswerDelta(id: Long, answer: String)

    fun onTurnFinished(id: Long, answer: String, saved: Boolean, retryPrompt: String?)
}

/**
 * The single owner of an embedded model session, one active generation, and a volatile chat.
 * A serial worker owns all SDK calls; the Android view only renders observer events.
 */
internal class EmbeddedChatController(private val applicationContext: Context, private val observer: ChatObserver) {
    private val worker = Executors.newSingleThreadExecutor()
    private val history = EphemeralChatHistory()
    private val engineCalls = EmbeddedChatGeneration()
    private val cancelled = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val activeGeneration = AtomicReference<GenerationHandle?>(null)
    private val state = ChatSessionState()
    private val ids = AtomicLong(0L)

    // Both values are accessed only by the serial worker.
    private var engine: EmbeddedRuntime? = null
    private var session: SessionId? = null

    fun prepare() {
        if (!state.beginPreparation()) return
        cancelled.set(false)
        observer.onPhase(ChatPhase.PREPARING)
        worker.execute {
            try {
                observer.onStatus("Opening local Harnex runtime…")
                val runtime = EmbeddedRuntime.open(applicationContext, EmbeddedConfiguration(MODEL_ID))
                engine = runtime
                EmbeddedModelPreparation.ensureReady(runtime, cancelled, destroyed, observer::onStatus)
                check(!cancelled.get() && !destroyed.get()) { "Preparation cancelled" }
                observer.onStatus("Preparing GGUF…")
                val prepared = runtime.prepare()
                check(prepared.ready) { "Model preparation failed: ${prepared.detail}" }
                session = runtime.openSession()
                check(!cancelled.get() && !destroyed.get()) { "Preparation cancelled" }
                check(state.completePreparation()) { "Preparation cancelled" }
                observer.onStatus("Ready · all inference stays on this device")
                observer.onDiagnostics("Verified model · process PSS ${Debug.getPss() / 1024} MB")
                observer.onPhase(ChatPhase.READY)
            } catch (failure: IllegalStateException) {
                closeRuntime()
                state.requirePreparation()
                observer.onStatus("Setup stopped · ${failure.message ?: "not ready"}")
                observer.onPhase(ChatPhase.NEEDS_MODEL)
            } catch (failure: IllegalArgumentException) {
                closeRuntime()
                state.requirePreparation()
                observer.onStatus("Setup stopped · ${failure.message ?: "invalid model configuration"}")
                observer.onPhase(ChatPhase.NEEDS_MODEL)
            }
        }
    }

    fun send(text: String): Boolean {
        val input = text.trim()
        if (input.isBlank()) {
            observer.onStatus("Write a message before sending")
            return false
        }
        if (input.length > EphemeralChatHistory.MAX_USER_CHARS) {
            observer.onStatus("Message limit: ${EphemeralChatHistory.MAX_USER_CHARS} characters")
            return false
        }
        if (destroyed.get() || state.current() != ChatPhase.READY) return false
        cancelled.set(false)
        if (!state.beginGeneration()) return false
        val id = ids.incrementAndGet()
        observer.onTurnStarted(id, input)
        observer.onPhase(ChatPhase.GENERATING)
        worker.execute { runTurn(id, input) }
        return true
    }

    fun stop() {
        if (!state.requestStop()) return
        cancelled.set(true)
        activeGeneration.get()?.cancel()
        observer.onPhase(ChatPhase.STOPPING)
        observer.onStatus("Stopping · waiting for the engine to finish…")
    }

    fun clearConversation(): Boolean {
        if (state.current() != ChatPhase.READY) return false
        worker.execute { history.reset() }
        observer.onStatus("New chat · context cleared")
        observer.onDiagnostics("Temporary conversation reset; verified GGUF retained")
        return true
    }

    fun close() {
        if (!destroyed.compareAndSet(false, true)) return
        cancelled.set(true)
        state.requirePreparation()
        activeGeneration.get()?.cancel()
        worker.execute { closeRuntime() }
        worker.shutdown()
    }

    private fun runTurn(id: Long, input: String) {
        try {
            check(!cancelled.get() && !destroyed.get()) { "Generation cancelled before start" }
            val sdk = checkNotNull(engine) { "Runtime not prepared" }
            val technicalSession = checkNotNull(session) { "Native session not prepared" }
            val messages = history.messagesFor(input)
            observer.onStatus("Generating · ${messages.size} explicit messages")
            val result = engineCalls.execute(
                sdk,
                technicalSession,
                messages,
                cancelled,
                destroyed,
                activeGeneration,
            ) { text -> observer.onAnswerDelta(id, text) }
            completeTurn(id, input, messages.size, result)
        } catch (failure: GenerationTimeout) {
            closeRuntime()
            phase.set(ChatPhase.NEEDS_MODEL)
            observer.onTurnFinished(id, failure.message ?: "Generation timed out; reinitialize the model", false, input)
            observer.onStatus("Generation timed out; model must be prepared again")
        } catch (failure: IllegalStateException) {
            observer.onTurnFinished(id, failure.message ?: "Generation unavailable", false, input)
            observer.onStatus("Generation stopped; no answer saved")
        } catch (failure: IllegalArgumentException) {
            observer.onTurnFinished(id, failure.message ?: "Invalid request", false, input)
            observer.onStatus("Invalid request; no answer saved")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            observer.onTurnFinished(id, failure.message ?: "Generation interrupted", false, input)
            observer.onStatus("Generation interrupted")
        } finally {
            activeGeneration.set(null)
            if (destroyed.get()) state.requirePreparation()
            observer.onPhase(state.settleGeneration())
        }
    }

    private fun completeTurn(id: Long, input: String, messageCount: Int, result: ChatGenerationOutcome) {
        when (val terminal = result.terminal) {
            is GenerationEvent.Completed -> {
                val answer = terminal.answerOutput.ifBlank { result.streamedAnswer }
                if (!state.acceptCompletion() || destroyed.get()) {
                    observer.onTurnFinished(id, "Stopped · partial output was not saved", false, input)
                } else if (answer.isBlank()) {
                    observer.onTurnFinished(id, "The model returned no answer", false, input)
                } else {
                    history.complete(input, answer)
                    observer.onTurnFinished(id, answer, true, null)
                    val metrics = terminal.metrics
                    val duration = SystemClock.elapsedRealtime() - result.startedAtMs
                    observer.onStatus("Answer complete · ${history.completedTurns()} temporary turns")
                    observer.onDiagnostics(formatMetrics(metrics, duration, messageCount))
                }
            }

            is GenerationEvent.Failed -> {
                observer.onTurnFinished(id, "${terminal.error.code} · ${terminal.error.message}", false, input)
                observer.onStatus("Generation stopped; no answer saved")
            }

            else -> {
                observer.onTurnFinished(id, "Generation ended without a terminal event", false, input)
                observer.onStatus("Generation did not complete")
            }
        }
    }

    private fun closeRuntime() {
        val runtime = engine
        val id = session
        session = null
        engine = null
        if (runtime != null) {
            if (id != null) runCatching { runtime.closeSession(id) }
            runCatching { runtime.close() }
        }
    }

    private companion object {
        const val MODEL_ID = "qwen35-2b-q4-k-m"

        fun formatMetrics(metrics: GenerationMetrics, elapsedMs: Long, messageCount: Int): String {
            val rate = metrics.decodeTokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            val firstToken = metrics.timeToFirstTokenMs?.toString() ?: "—"
            return "TTFT $firstToken ms · ${metrics.totalMs} ms engine · $elapsedMs ms wall · " +
                "${metrics.outputTokens ?: "—"} tokens · $rate tok/s · PSS ${Debug.getPss() / 1024} MB · " +
                "$messageCount messages"
        }
    }
}
