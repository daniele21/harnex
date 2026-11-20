package io.github.daniele21.localllm.externalembedded

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
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

    fun onVisionState(ready: Boolean, preparing: Boolean) {}

    fun onVisionStatus(status: String) {}
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
    private val visionPreparing = AtomicBoolean(false)
    private val visionReady = AtomicBoolean(false)
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
                visionReady.set(runtime.visionProjectorReadiness())
                observer.onVisionState(visionReady.get(), false)
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

    fun prepareVision() {
        if (destroyed.get() || state.current() != ChatPhase.READY || !visionPreparing.compareAndSet(false, true)) return
        cancelled.set(false)
        observer.onVisionState(visionReady.get(), true)
        worker.execute {
            try {
                val sdk = checkNotNull(engine) { "Prepare Qwen before the image projector" }
                observer.onVisionStatus("Downloading and verifying vision projector…")
                var lastUpdate = 0L
                sdk.prepareVisionProjector(
                    observer = DownloadProgressObserver { value ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 500L) {
                            lastUpdate = now
                            observer.onVisionStatus(
                                "Vision download · ${value.bytesDownloaded / MIB} / ${value.expectedBytes / MIB} MB",
                            )
                        }
                    },
                    cancellationToken = DownloadCancellationToken { cancelled.get() || destroyed.get() },
                )
                visionReady.set(true)
                observer.onVisionStatus("Vision model verified · choose a photo to ask Qwen")
            } catch (failure: IllegalStateException) {
                visionReady.set(false)
                observer.onVisionStatus("Vision unavailable · ${failure.message ?: "setup failed"}")
            } catch (failure: IllegalArgumentException) {
                visionReady.set(false)
                observer.onVisionStatus("Vision unavailable · invalid projector")
            } finally {
                visionPreparing.set(false)
                observer.onVisionState(visionReady.get(), false)
            }
        }
    }

    fun sendImage(text: String, imageBytes: ByteArray): Boolean {
        if (!visionReady.get()) {
            observer.onVisionStatus("Tap Prepare vision first")
            return false
        }
        if (imageBytes.isEmpty() || imageBytes.size > MAX_IMAGE_BYTES) {
            observer.onVisionStatus("Selected image is invalid or too large")
            return false
        }
        return sendInternal(text, imageBytes.copyOf())
    }

    fun send(text: String): Boolean = sendInternal(text, null)

    private fun sendInternal(text: String, imageBytes: ByteArray?): Boolean {
        val input = text.trim()
        val validationError = when {
            input.isBlank() -> "Write a message before sending"

            input.length > EphemeralChatHistory.MAX_USER_CHARS ->
                "Message limit: ${EphemeralChatHistory.MAX_USER_CHARS} characters"

            else -> null
        }
        if (validationError != null) {
            observer.onStatus(validationError)
            return false
        }
        if (destroyed.get() || visionPreparing.get() || state.current() != ChatPhase.READY) return false
        cancelled.set(false)
        if (!state.beginGeneration()) return false
        val id = ids.incrementAndGet()
        observer.onTurnStarted(id, if (imageBytes != null) "PHOTO · $input" else input)
        observer.onPhase(ChatPhase.GENERATING)
        worker.execute { runTurn(id, input, imageBytes) }
        return true
    }

    fun stop() {
        if (visionPreparing.get()) {
            cancelled.set(true)
            observer.onVisionStatus("Cancelling vision model setup…")
            return
        }
        if (!state.requestStop()) return
        cancelled.set(true)
        activeGeneration.get()?.cancel()
        observer.onPhase(ChatPhase.STOPPING)
        observer.onStatus("Stopping · waiting for the engine to finish…")
    }

    fun isGenerating(): Boolean = state.current() == ChatPhase.GENERATING && !destroyed.get()

    fun clearConversation(): Boolean {
        if (state.current() != ChatPhase.READY || visionPreparing.get()) return false
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

    private fun runTurn(id: Long, input: String, imageBytes: ByteArray?) {
        try {
            check(!cancelled.get() && !destroyed.get()) { "Generation cancelled before start" }
            val sdk = checkNotNull(engine) { "Runtime not prepared" }
            val technicalSession = checkNotNull(session) { "Native session not prepared" }
            val messages = if (imageBytes == null) history.messagesFor(input) else emptyList()
            observer.onStatus(if (imageBytes == null) "Generating · ${messages.size} explicit messages" else "Analyzing photo locally…")
            val result = engineCalls.execute(
                sdk,
                technicalSession,
                messages,
                cancelled,
                destroyed,
                activeGeneration,
                imageBytes,
                input,
            ) { text ->
                if (state.current() == ChatPhase.GENERATING) observer.onAnswerDelta(id, text)
            }
            completeTurn(id, input, messages.size, result, imageBytes != null)
        } catch (failure: GenerationTimeout) {
            closeRuntime()
            state.requirePreparation()
            observer.onTurnFinished(id, failure.message ?: "Generation timed out; reinitialize the model", false, input)
            observer.onStatus("Generation timed out; model must be prepared again")
        } catch (failure: IllegalStateException) {
            observer.onTurnFinished(id, failure.message ?: "Generation unavailable", false, input)
            observer.onStatus("Generation stopped; no answer saved")
        } catch (failure: IllegalArgumentException) {
            observer.onTurnFinished(id, failure.message ?: "Invalid request", false, input)
            observer.onStatus("Invalid request; no answer saved")
        } catch (failure: InterruptedException) {
            closeRuntime()
            state.requirePreparation()
            Thread.currentThread().interrupt()
            observer.onTurnFinished(id, failure.message ?: "Generation interrupted; prepare the model again", false, input)
            observer.onStatus("Generation interrupted; model must be prepared again")
        } finally {
            activeGeneration.set(null)
            if (destroyed.get()) state.requirePreparation()
            observer.onPhase(state.settleGeneration())
        }
    }

    private fun completeTurn(id: Long, input: String, messageCount: Int, result: ChatGenerationOutcome, imageQuestion: Boolean) {
        when (val terminal = result.terminal) {
            is GenerationEvent.Completed -> {
                val answer = terminal.answerOutput.ifBlank { result.streamedAnswer }
                if (!state.acceptCompletion() || destroyed.get()) {
                    observer.onTurnFinished(id, "Stopped · partial output was not saved", false, input)
                } else if (answer.isBlank()) {
                    observer.onTurnFinished(id, "The model returned no answer", false, input)
                } else {
                    if (!imageQuestion) history.complete(input, answer)
                    observer.onTurnFinished(id, answer, true, null)
                    val metrics = terminal.metrics
                    val duration = SystemClock.elapsedRealtime() - result.startedAtMs
                    observer.onStatus(
                        if (imageQuestion) {
                            "Image answer complete · photo not saved"
                        } else {
                            "Answer complete · ${history.completedTurns()} temporary turns"
                        },
                    )
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
        visionReady.set(false)
        observer.onVisionState(false, false)
        if (runtime != null) {
            if (id != null) runCatching { runtime.closeSession(id) }
            runCatching { runtime.close() }
        }
    }

    private companion object {
        const val MODEL_ID = "qwen35-2b-q4-k-m"
        const val MIB = 1024L * 1024L
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024

        fun formatMetrics(metrics: GenerationMetrics, elapsedMs: Long, messageCount: Int): String {
            val rate = metrics.decodeTokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
            val firstToken = metrics.timeToFirstTokenMs?.toString() ?: "—"
            return "TTFT $firstToken ms · ${metrics.totalMs} ms engine · $elapsedMs ms wall · " +
                "${metrics.outputTokens ?: "—"} tokens · $rate tok/s · PSS ${Debug.getPss() / 1024} MB · " +
                "$messageCount messages"
        }
    }
}
