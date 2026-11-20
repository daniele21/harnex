package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.widget.TextView
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.embedded.EmbeddedConfiguration
import io.github.daniele21.localllm.embedded.EmbeddedModelReadiness
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import io.github.daniele21.localllm.install.ModelInstallationObserver
import io.github.daniele21.localllm.install.ModelInstallationResult
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The app owns chat history and UI; Harnex owns the embedded model/runtime lifecycle. */
class EmbeddedSmokeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val destroying = AtomicBoolean(false)
    private val activeGeneration = AtomicReference<GenerationHandle?>(null)
    private val history = EphemeralChatHistory()

    // The in-process runtime and stateless technical session are worker-thread owners.
    private var sdk: EmbeddedRuntime? = null
    private var session: SessionId? = null

    // User-visible state is owned by the Android main thread.
    private var ready = false
    private var busy = false
    private lateinit var screen: EmbeddedChatScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EmbeddedChatScreen(
            activity = this,
            onPrepare = ::beginSetup,
            onSend = ::sendMessage,
            onStop = {
                cancelled.set(true)
                activeGeneration.get()?.cancel()
                showStatus("Cancellation requested…")
            },
            onNewChat = {
                if (!busy) {
                    history.reset()
                    screen.prompt.text.clear()
                    screen.resetConversation()
                    screen.setDiagnostics("History cleared · model remains ready")
                    showStatus("New chat · no earlier messages are sent")
                }
            },
        )
        setContentView(screen.build())
        screen.updateControls(ready, busy)
    }

    private fun beginSetup() {
        if (busy || ready) return
        cancelled.set(false)
        busy = true
        screen.updateControls(ready, busy)
        worker.execute {
            try {
                showStatus("Opening Harnex in-process runtime…")
                val engine = EmbeddedRuntime.open(applicationContext, EmbeddedConfiguration(MODEL_ID))
                sdk = engine
                ensureModel(engine)
                check(!cancelled.get()) { "Setup cancelled" }
                showStatus("Preparing GGUF in local runtime…")
                val result = engine.prepare()
                check(result.ready) { "Prepare failed: ${result.detail}" }
                session = engine.openSession()
                postUi {
                    ready = true
                    busy = false
                    screen.setStatus("Ready · inference stays on this device")
                    screen.setDiagnostics("Model verified · app PSS ${processPssMb()} MB · no cloud")
                    screen.updateControls(ready, busy)
                }
            } catch (failure: IllegalStateException) {
                setupFailed(failure.message ?: "Setup stopped")
            } catch (failure: IllegalArgumentException) {
                setupFailed(failure.message ?: "Invalid model configuration")
            }
        }
    }

    private fun setupFailed(reason: String) {
        closeRuntime()
        postUi {
            ready = false
            busy = false
            screen.setStatus("Setup stopped · $reason")
            screen.updateControls(ready, busy)
        }
    }

    private fun ensureModel(engine: EmbeddedRuntime) {
        when (engine.modelReadiness()) {
            EmbeddedModelReadiness.VERIFIED -> {
                showStatus("Previously installed model verified")
                return
            }

            EmbeddedModelReadiness.INVALID -> error("Installed GGUF is invalid; repair required")

            EmbeddedModelReadiness.NOT_INSTALLED -> Unit
        }
        showStatus("Downloading the reviewed GGUF…")
        var lastUpdate = 0L
        val result = engine.downloadModel(
            observer = DownloadProgressObserver { progress ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate > 350L) {
                    lastUpdate = now
                    val total = "/ ${progress.expectedBytes / MIB} MB"
                    showStatus("Downloading · ${progress.bytesDownloaded / MIB} MB$total")
                }
            },
            cancellationToken = DownloadCancellationToken { cancelled.get() || destroying.get() },
        )
        val handle = when (result) {
            is ModelDownloadResult.Success -> result.handle
            is ModelDownloadResult.Cancelled -> error("Download cancelled")
            is ModelDownloadResult.Failure -> error("Download failed: ${result.failure.code}")
            is ModelDownloadResult.AlreadyRunning -> error("Download already running")
        }
        check(!cancelled.get()) { "Setup cancelled" }
        val installed = engine.installModel(
            handle,
            ModelInstallationObserver { progress ->
                showStatus("Installing GGUF · ${progress.stage.name.lowercase().replace('_', ' ')}")
            },
        )
        when (installed) {
            is ModelInstallationResult.Success -> Unit
            is ModelInstallationResult.Failure -> error("Installation failed: ${installed.code} (${installed.detail})")
        }
        check(engine.modelReadiness() == EmbeddedModelReadiness.VERIFIED) {
            "Installed model failed verification"
        }
    }

    private fun sendMessage() {
        if (!ready || busy) return
        val input = screen.prompt.text.toString().trim()
        if (input.isBlank()) {
            showStatus("Write a message before sending")
            return
        }
        if (input.length > EphemeralChatHistory.MAX_USER_CHARS) {
            showStatus("Messages are limited to ${EphemeralChatHistory.MAX_USER_CHARS} characters")
            return
        }
        busy = true
        cancelled.set(false)
        screen.prompt.text.clear()
        screen.updateControls(ready, busy)
        screen.addBubble("YOU", input, true)
        val assistant = screen.addBubble("HARNEX · QWEN3.5", "Thinking locally…", false)
        worker.execute { runGeneration(input, assistant) }
    }

    private fun runGeneration(input: String, bubble: TextView) {
        val engine = sdk
        val technicalSession = session
        if (engine == null || technicalSession == null) {
            completeTurnFailure(input, bubble, "Runtime is not ready")
            postUi {
                ready = false
                busy = false
                screen.updateControls(ready, busy)
            }
            return
        }
        try {
            val messages = history.messagesFor(input)
            showStatus("Generating · ${messages.size} explicit messages")
            val result = EmbeddedChatGeneration().execute(
                engine = engine,
                session = technicalSession,
                messages = messages,
                cancelled = cancelled,
                destroying = destroying,
                activeGeneration = activeGeneration,
                onAnswerDelta = { answer -> postUi { screen.updateBubble(bubble, answer) } },
            )
            when (val terminal = result.terminal) {
                is GenerationEvent.Completed -> {
                    val answer = terminal.answerOutput.ifBlank { result.streamedAnswer }
                    when {
                        cancelled.get() || destroying.get() ->
                            completeTurnFailure(input, bubble, "Cancelled · partial output was not saved")
                        answer.isBlank() ->
                            completeTurnFailure(input, bubble, "The model returned no answer")
                        else -> {
                            history.complete(input, answer)
                            postUi {
                                screen.updateBubble(bubble, answer)
                                screen.setStatus("Answer complete · ${history.completedTurns()} temporary turns")
                                screen.setDiagnostics(
                                    formatDiagnostics(
                                        terminal.metrics,
                                        messages.size,
                                        result.startedAtMs,
                                        processPssMb(),
                                    ),
                                )
                            }
                        }
                    }
                }

                is GenerationEvent.Failed ->
                    completeTurnFailure(input, bubble, "${terminal.error.code} · ${terminal.error.message}")

                else -> completeTurnFailure(input, bubble, "Generation ended without a terminal event")
            }
        } catch (failure: GenerationTimeout) {
            closeRuntime()
            postUi { ready = false }
            completeTurnFailure(input, bubble, failure.message ?: "Generation timed out")
        } catch (failure: IllegalStateException) {
            completeTurnFailure(input, bubble, failure.message ?: "Generation stopped")
        } catch (failure: IllegalArgumentException) {
            completeTurnFailure(input, bubble, failure.message ?: "Invalid request")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            completeTurnFailure(input, bubble, "Generation interrupted")
        } finally {
            activeGeneration.set(null)
            postUi {
                busy = false
                screen.updateControls(ready, busy)
            }
        }
    }

    private fun completeTurnFailure(input: String, bubble: TextView, reason: String) {
        postUi {
            screen.updateBubble(bubble, "Not saved · $reason")
            screen.prompt.setText(input)
            screen.setStatus("Stopped · $reason")
            screen.setDiagnostics("No failed or partial answer was added to history · PSS ${processPssMb()} MB")
        }
    }

    private fun showStatus(message: String) {
        postUi { screen.setStatus(message) }
    }

    private fun postUi(action: () -> Unit) {
        runOnUiThread { if (!destroying.get() && !isDestroyed) action() }
    }

    private fun closeRuntime() {
        val engine = sdk
        val id = session
        session = null
        sdk = null
        if (engine != null) {
            if (id != null) runCatching { engine.closeSession(id) }
            runCatching { engine.close() }
        }
    }

    override fun onDestroy() {
        destroying.set(true)
        cancelled.set(true)
        activeGeneration.get()?.cancel()
        worker.execute { closeRuntime() }
        worker.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val MODEL_ID = "qwen35-2b-q4-k-m"
        const val MIB = 1024L * 1024L
    }
}

private fun processPssMb() = Debug.getPss() / 1024

private fun formatDiagnostics(metrics: GenerationMetrics, messagesSent: Int, started: Long, pssMb: Int): String {
    val rate = metrics.decodeTokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
    val ttft = metrics.timeToFirstTokenMs?.toString() ?: "—"
    val elapsed = SystemClock.elapsedRealtime() - started
    return "TTFT $ttft ms · ${metrics.totalMs} ms engine · $elapsed ms wall · " +
        "${metrics.outputTokens ?: "—"} tokens · $rate tok/s · PSS $pssMb MB · $messagesSent messages"
}
