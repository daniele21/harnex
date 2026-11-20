package io.github.daniele21.localllm.externalembedded

import android.content.Context
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.embedded.EmbeddedConfiguration
import io.github.daniele21.localllm.embedded.EmbeddedRuntime
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal data class ModelTransferOutcome(val success: Boolean, val retryable: Boolean, val status: String)

/** Single owner of the actual verified SDK download/install; no duplicate HTTP policy. */
internal object ModelTransferTask {
    fun execute(
        context: Context,
        kind: ModelTransferKind,
        cancelled: AtomicBoolean,
        notifyProgress: (String, Int) -> Unit,
    ): ModelTransferOutcome {
        val app = context.applicationContext
        val runtime = EmbeddedRuntime.open(app, EmbeddedConfiguration("qwen35-2b-q4-k-m"))
        return try {
            when (kind) {
                ModelTransferKind.MODEL -> EmbeddedModelPreparation.ensureReady(
                    engine = runtime,
                    cancelled = cancelled,
                    destroyed = cancelled,
                    onStatus = { status ->
                        if (!cancelled.get()) {
                            ModelTransferState.update(app, kind, ModelTransferPhase.RUNNING, status)
                            notifyProgress(status, -1)
                        }
                    },
                    preservePartialOnCancel = true,
                )

                ModelTransferKind.VISION -> runtime.vision.prepare(
                    observer = DownloadProgressObserver { progress ->
                        val fraction = ((100L * progress.bytesDownloaded) / progress.expectedBytes).toInt()
                        val status = "Vision download · ${progress.bytesDownloaded / MIB} / ${progress.expectedBytes / MIB} MB"
                        if (!cancelled.get()) {
                            ModelTransferState.update(app, kind, ModelTransferPhase.RUNNING, status)
                            notifyProgress(status, fraction)
                        }
                    },
                    cancellationToken = DownloadCancellationToken(cancelled::get),
                )
            }
            if (cancelled.get()) {
                ModelTransferOutcome(false, true, "Transfer paused")
            } else {
                ModelTransferOutcome(true, false, "${kind.label} verified and installed")
            }
        } catch (failure: IOException) {
            ModelTransferOutcome(
                false,
                true,
                "Network interrupted (${failure.javaClass.simpleName}) · transfer will resume",
            )
        } catch (failure: IllegalStateException) {
            val reason = failure.message.orEmpty()
            val retryable = reason.contains("NETWORK_FAILURE") || reason.contains("DOWNLOAD_HTTP_ERROR") ||
                reason.contains("DOWNLOAD_SIZE_MISMATCH") || reason.contains("already running", ignoreCase = true) ||
                reason.contains("cancelled", ignoreCase = true)
            ModelTransferOutcome(false, retryable, "Download unavailable · ${reason.take(90)}")
        } finally {
            runtime.close()
        }
    }

    private const val MIB = 1024L * 1024L
}
