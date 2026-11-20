package io.github.daniele21.localllm.externalembedded

import android.os.SystemClock
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.embedded.EmbeddedModelReadiness
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import io.github.daniele21.localllm.install.ModelInstallationObserver
import io.github.daniele21.localllm.install.ModelInstallationResult
import java.util.concurrent.atomic.AtomicBoolean

/** Uses Harnex's reviewed download/installation policy; it never owns model files. */
internal object EmbeddedModelPreparation {
    fun ensureReady(
        engine: EmbeddedRuntime,
        cancelled: AtomicBoolean,
        destroyed: AtomicBoolean,
        onStatus: (String) -> Unit,
    ) {
        when (engine.modelReadiness()) {
            EmbeddedModelReadiness.VERIFIED -> {
                onStatus("Previously installed model verified")
                return
            }

            EmbeddedModelReadiness.INVALID -> error("Installed GGUF is invalid; repair required")
            EmbeddedModelReadiness.NOT_INSTALLED -> Unit
        }
        onStatus("Downloading the reviewed GGUF…")
        var lastUpdate = 0L
        val result = engine.downloadModel(
            observer = DownloadProgressObserver { progress ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 350L) {
                    lastUpdate = now
                    onStatus("Downloading · ${progress.bytesDownloaded / MIB} / ${progress.expectedBytes / MIB} MB")
                }
            },
            cancellationToken = DownloadCancellationToken { cancelled.get() || destroyed.get() },
        )
        val handle = when (result) {
            is ModelDownloadResult.Success -> result.handle
            is ModelDownloadResult.Cancelled -> error("Download cancelled")
            is ModelDownloadResult.Failure -> error("Download failed: ${result.failure.code}")
            is ModelDownloadResult.AlreadyRunning -> error("Download already running")
        }
        check(!cancelled.get() && !destroyed.get()) { "Preparation cancelled" }
        val installed = engine.installModel(
            handle,
            ModelInstallationObserver { onStatus("Installing GGUF · ${it.stage.name.lowercase().replace('_', ' ')}") },
        )
        when (installed) {
            is ModelInstallationResult.Success -> Unit
            is ModelInstallationResult.Failure -> error("Install failed: ${installed.code} (${installed.detail})")
        }
        check(engine.modelReadiness() == EmbeddedModelReadiness.VERIFIED) { "Installed model failed verification" }
    }

    private const val MIB = 1024L * 1024L
}
