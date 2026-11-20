package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.GenerationOverrides
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Independent single-APK chat demo consuming Maven artifacts only.
 * History and rendering belong exclusively to this Activity, never to Harnex.
 */
class EmbeddedSmokeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val destroying = AtomicBoolean(false)
    private val activeGeneration = AtomicReference<GenerationHandle?>(null)
    private val history = EphemeralChatHistory()

    // SDK and technical session are used only on the dedicated worker.
    private var sdk: EmbeddedRuntime? = null
    private var session: SessionId? = null

    // UI state stays on the Android main thread.
    private var ready = false
    private var busy = false
    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var prompt: EditText
    private lateinit var setupButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 252))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            setBackgroundColor(Color.rgb(25, 29, 40))
        }
        header.addView(label("Harnex · Local Chat", 22f, Color.WHITE))
        header.addView(label("EMBEDDED DEMO  ·  QWEN3.5 2B  ·  ON-DEVICE", 11f, Color.rgb(188, 201, 224)))
        outer.addView(header)

        val setupPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        status = label("Model not ready · start local setup", 15f, Color.rgb(38, 54, 79)).apply {
            contentDescription = "Model status"
        }
        diagnostics = label("No generation yet · temporary history only", 12f, Color.rgb(91, 103, 121))
        setupButton = actionButton("Prepare local model", true).apply {
            setOnClickListener { beginSetup() }
        }
        setupPanel.addView(status)
        setupPanel.addView(diagnostics)
        setupPanel.addView(
            setupButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(8)
            },
        )
        outer.addView(setupPanel)

        scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll.addView(messages)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        showWelcome()

        outer.addView(buildComposer())

        outer.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(0, bars.top, 0, maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(outer)
        outer.requestApplyInsets()
        updateControls()
    }

    private fun buildComposer(): LinearLayout {
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        prompt = EditText(this).apply {
            hint = "Type a message…"
            minLines = 1
            maxLines = 3
            textSize = 16f
            setSingleLine(false)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        composer.addView(prompt, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sendButton = actionButton("Send", true).apply {
            setOnClickListener { sendMessage() }
        }
        stopButton = actionButton("Stop", false).apply {
            setOnClickListener {
                cancelled.set(true)
                activeGeneration.get()?.cancel()
                showStatus("Cancellation requested…")
            }
        }
        resetButton = actionButton("New chat", false).apply {
            setOnClickListener {
                if (!busy) {
                    history.reset()
                    prompt.text.clear()
                    messages.removeAllViews()
                    showWelcome()
                    diagnostics.text = "History cleared · model remains ready"
                    showStatus("New chat · no earlier messages are sent")
                }
            }
        }
        for (button in listOf(sendButton, stopButton, resetButton)) {
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                },
            )
        }
        composer.addView(actions)
        return composer
    }

    private fun beginSetup() {
        if (busy || ready) return
        cancelled.set(false)
        busy = true
        updateControls()
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
                    showStatus("Ready · inference stays on this device")
                    diagnostics.text = "Model verified · app PSS ${pssMb()} MB · no cloud"
                    updateControls()
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
            showStatus("Setup stopped · $reason")
            updateControls()
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
        val input = prompt.text.toString().trim()
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
        prompt.text.clear()
        updateControls()
        addBubble("YOU", input, true)
        val assistant = addBubble("HARNEX · QWEN3.5", "Thinking locally…", false)
        worker.execute { runGeneration(input, assistant) }
    }

    private fun runGeneration(input: String, bubble: TextView) {
        val engine = sdk
        val technicalSession = session
        if (engine == null || technicalSession == null) {
            completeTurnFailure(input, bubble, "Runtime is not ready")
            postUi {
                busy = false
                ready = false
                updateControls()
            }
            return
        }
        try {
            val requestMessages = history.messagesFor(input)
            val finished = CountDownLatch(1)
            val outcome = AtomicReference<GenerationEvent?>(null)
            val stream = StringBuilder()
            var lastRendered = 0L
            val start = SystemClock.elapsedRealtime()
            showStatus("Generating · ${requestMessages.size} explicit messages")
            val handle = engine.generate(
                technicalSession,
                requestMessages,
                GenerationListener { event ->
                    when (event) {
                        is GenerationEvent.TextDelta -> if (event.contentType == GenerationContentType.ANSWER) {
                            val text = synchronized(stream) {
                                stream.append(event.text)
                                stream.toString()
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastRendered > 90L) {
                                lastRendered = now
                                updateBubble(bubble, text)
                            }
                        }

                        is GenerationEvent.Completed, is GenerationEvent.Failed -> {
                            if (outcome.compareAndSet(null, event)) finished.countDown()
                        }

                        else -> Unit
                    }
                },
                overrides = GenerationOverrides(maxOutputTokens = 256),
            )
            activeGeneration.set(handle)
            var elapsedChecks = 0
            while (!finished.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled.get() || destroying.get()) handle.cancel()
                if (++elapsedChecks >= 480) {
                    handle.cancel()
                    throw GenerationTimeout()
                }
            }
            activeGeneration.compareAndSet(handle, null)
            when (val terminal = outcome.get()) {
                is GenerationEvent.Completed -> {
                    val answer = terminal.answerOutput.ifBlank { synchronized(stream) { stream.toString() } }
                    if (cancelled.get() || destroying.get()) {
                        completeTurnFailure(input, bubble, "Cancelled · partial output was not saved")
                    } else if (answer.isBlank()) {
                        completeTurnFailure(input, bubble, "The model returned no answer")
                    } else {
                        history.complete(input, answer)
                        updateBubble(bubble, answer)
                        showMetrics(terminal.metrics, requestMessages.size, start)
                    }
                }

                is GenerationEvent.Failed -> completeTurnFailure(input, bubble, "${terminal.error.code} · ${terminal.error.message}")

                else -> completeTurnFailure(input, bubble, "Generation ended without a terminal event")
            }
        } catch (failure: IllegalStateException) {
            if (failure is GenerationTimeout) {
                closeRuntime()
                postUi { ready = false }
            }
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
                updateControls()
            }
        }
    }

    private fun showMetrics(metrics: GenerationMetrics, messagesSent: Int, started: Long) {
        val rate = metrics.decodeTokensPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        val ttft = metrics.timeToFirstTokenMs?.toString() ?: "—"
        val elapsed = SystemClock.elapsedRealtime() - started
        postUi {
            showStatus("Answer complete · ${history.completedTurns()} temporary turns")
            diagnostics.text = "TTFT $ttft ms · ${metrics.totalMs} ms engine · $elapsed ms wall · " +
                "${metrics.outputTokens ?: "—"} tokens · $rate tok/s · PSS ${pssMb()} MB · $messagesSent messages"
        }
    }

    private fun completeTurnFailure(input: String, bubble: TextView, reason: String) {
        updateBubble(bubble, "Not saved · $reason")
        postUi {
            prompt.setText(input)
            showStatus("Stopped · $reason")
            diagnostics.text = "No failed or partial answer was added to history · PSS ${pssMb()} MB"
        }
    }

    private fun addBubble(author: String, body: String, isUser: Boolean): TextView {
        val item = TextView(this).apply {
            text = "$author\n$body"
            textSize = 15f
            setTextColor(if (isUser) Color.WHITE else Color.rgb(34, 47, 70))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(if (isUser) Color.rgb(37, 91, 191) else Color.WHITE)
                cornerRadius = dp(15).toFloat()
            }
        }
        messages.addView(
            item,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(10)
                if (isUser) marginStart = dp(35) else marginEnd = dp(25)
            },
        )
        scrollToBottom()
        return item
    }

    private fun updateBubble(bubble: TextView, text: String) {
        postUi {
            bubble.text = "HARNEX · QWEN3.5\n$text"
            scrollToBottom()
        }
    }

    private fun showWelcome() {
        messages.addView(
            label(
                "Private by design\n\nThis chat runs with Qwen3.5 2B inside this app. " +
                    "Messages stay in memory only, and only the last six completed turns " +
                    "are considered for each request. Start by preparing the model.",
                15f,
                Color.rgb(78, 91, 114),
            ).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) },
        )
    }

    private fun showStatus(message: String) {
        postUi { status.text = message }
    }

    private fun updateControls() {
        setupButton.visibility = if (ready) View.GONE else View.VISIBLE
        setupButton.isEnabled = !busy
        sendButton.isEnabled = ready && !busy
        prompt.isEnabled = ready && !busy
        stopButton.isEnabled = busy
        resetButton.isEnabled = ready && !busy
    }

    private fun scrollToBottom() {
        scroll.post { if (!isDestroyed) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }

    private fun actionButton(text: String, primary: Boolean) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) Color.WHITE else Color.rgb(36, 50, 71))
        backgroundTintList = ColorStateList.valueOf(
            if (primary) Color.rgb(37, 91, 191) else Color.rgb(231, 236, 245),
        )
    }

    private fun postUi(action: () -> Unit) {
        runOnUiThread { if (!destroying.get() && !isDestroyed) action() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun pssMb() = Debug.getPss() / 1024

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

    private class GenerationTimeout : IllegalStateException("Generation timed out; reinitialize model before retrying")

    private companion object {
        const val MODEL_ID = "qwen35-2b-q4-k-m"
        const val MIB = 1024L * 1024L
    }
}
