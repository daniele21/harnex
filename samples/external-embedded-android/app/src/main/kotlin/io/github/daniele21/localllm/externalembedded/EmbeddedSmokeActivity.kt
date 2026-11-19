package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.ConversationRole
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.embedded.EmbeddedConfiguration
import io.github.daniele21.localllm.embedded.EmbeddedModelReadiness
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import io.github.daniele21.localllm.install.ModelInstallationResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Deliberately independent one-APK proof: no Host, Binder, project dependency or chat manager.
 * Installs the reviewed GGUF on request and runs two stateless Messages requests.
 */
class EmbeddedSmokeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var status: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Ready to run the embedded model smoke test" }
        runButton = Button(this).apply {
            text = "Download / Install / Generate"
            setOnClickListener {
                isEnabled = false
                cancelled.set(false)
                worker.execute {
                    runSmoke()
                    runOnUiThread { if (!isDestroyed) isEnabled = true }
                }
            }
        }
        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener { cancelled.set(true) }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(runButton)
            addView(cancelButton)
            addView(status)
        }
        setContentView(layout)
    }

    private fun runSmoke() {
        var sdk: EmbeddedRuntime? = null
        var session: SessionId? = null
        try {
            show("Opening in-process SDK")
            sdk = EmbeddedRuntime.open(applicationContext, EmbeddedConfiguration("qwen35-2b-q4-k-m"))
            ensureModel(sdk)
            check(!cancelled.get()) { "Smoke test cancelled" }
            val ready = sdk.prepare()
            check(ready.ready) { "Prepare failed: ${ready.detail}" }
            session = sdk.openSession()

            streamOnce(
                sdk,
                session,
                listOf(
                    ConversationMessage(ConversationRole.USER, "My name is Mario."),
                    ConversationMessage(ConversationRole.ASSISTANT, "Hello Mario."),
                    ConversationMessage(ConversationRole.USER, "What is my name?"),
                ),
            )
            check(!cancelled.get()) { "Smoke test cancelled" }
            streamOnce(
                sdk,
                session,
                listOf(ConversationMessage(ConversationRole.USER, "What is my name?")),
            )
            show("Completed two explicit, stateless Messages requests")
        } catch (error: IllegalStateException) {
            show("Stopped: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: IllegalArgumentException) {
            show("Invalid input: ${error.javaClass.simpleName}")
        } finally {
            session?.let { current -> runCatching { sdk?.closeSession(current) } }
            runCatching { sdk?.close() }
        }
    }

    private fun ensureModel(sdk: EmbeddedRuntime) {
        when (sdk.modelReadiness()) {
            EmbeddedModelReadiness.VERIFIED -> return
            EmbeddedModelReadiness.INVALID -> error("Installed GGUF is invalid; repair is required")
            EmbeddedModelReadiness.NOT_INSTALLED -> Unit
        }
        show("Downloading verified GGUF to app-private storage")
        val downloaded = sdk.downloadModel(
            observer = DownloadProgressObserver { progress ->
                show("Download: ${progress.bytesDownloaded} / ${progress.expectedBytes} bytes")
            },
            cancellationToken = DownloadCancellationToken { cancelled.get() },
        )
        val handle = when (downloaded) {
            is ModelDownloadResult.Success -> downloaded.handle
            is ModelDownloadResult.Cancelled -> error("Download cancelled")
            is ModelDownloadResult.Failure -> error("Download failed: ${downloaded.failure.code}")
            is ModelDownloadResult.AlreadyRunning -> error("Download already running")
        }
        show("Installing verified GGUF")
        val installed = sdk.installModel(handle)
        check(installed is ModelInstallationResult.Success) {
            "Installation failed: ${(installed as ModelInstallationResult.Failure).code}"
        }
        check(sdk.modelReadiness() == EmbeddedModelReadiness.VERIFIED) {
            "Installed model did not verify"
        }
    }

    private fun streamOnce(sdk: EmbeddedRuntime, session: SessionId, messages: List<ConversationMessage>) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val handle = sdk.generate(
            session,
            messages,
            GenerationListener { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> show(event.text)

                    is GenerationEvent.Completed -> {
                        show("Answer: ${event.answerOutput}")
                        done.countDown()
                    }

                    is GenerationEvent.Failed -> {
                        failure.set(event.error.code)
                        done.countDown()
                    }

                    else -> Unit
                }
            },
        )
        var checks = 0
        while (!done.await(250, TimeUnit.MILLISECONDS)) {
            if (cancelled.get()) handle.cancel()
            if (++checks >= 480) {
                handle.cancel()
                error("Generation exceeded 120 seconds")
            }
        }
        check(failure.get() == null) { "Generation failed: ${failure.get()}" }
    }

    private fun show(message: String) {
        runOnUiThread {
            if (!isDestroyed) status.text = message
        }
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }
}
