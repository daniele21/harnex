package io.github.daniele21.localllm.download

import io.github.daniele21.localllm.catalog.CatalogGgufArtifact
import io.github.daniele21.localllm.contracts.ModelDigest

@JvmInline
value class DownloadOperationId(val value: String)

@JvmInline
value class VerifiedDownloadHandle(val value: String)

/** A system-preempted Android transfer keeps bounded partial bytes for a later verified resume. */
data class ModelDownloadRequest @JvmOverloads constructor(val artifact: CatalogGgufArtifact, val preservePartialOnCancel: Boolean = false)

data class DownloadConfiguration(
    val maxArtifactBytes: Long = 8_000_000_000L,
    val storageHeadroomBytes: Long = 268_435_456L,
    val maxRedirects: Int = 5,
    val connectTimeoutMs: Int = 30_000,
    val readTimeoutMs: Int = 30_000,
    val bufferSizeBytes: Int = 65_536,
    val progressMinBytesDelta: Long = 1_048_576L,
    val progressMinIntervalMs: Long = 500L,
    val orphanRetentionMs: Long = 86_400_000L,
    val retryPolicy: DownloadRetryPolicy = DownloadRetryPolicy(),
) {
    init {
        require(maxArtifactBytes > 0)
        require(storageHeadroomBytes >= 0)
        require(maxRedirects >= 0)
        require(connectTimeoutMs > 0)
        require(readTimeoutMs > 0)
        require(bufferSizeBytes in 4_096..1_048_576)
        require(progressMinBytesDelta > 0)
        require(progressMinIntervalMs >= 0)
        require(orphanRetentionMs >= 0)
    }
}

data class DownloadRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val jitterRatio: Double = 0.20,
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMs >= 0)
        require(maxDelayMs >= initialDelayMs)
        require(jitterRatio in 0.0..1.0)
    }
}

enum class DownloadStage {
    PREPARING,
    CONNECTING,
    DOWNLOADING,
    RETRY_WAIT,
    VERIFYING,
    COMPLETED,
    CANCELLED,
    FAILED,
}

data class DownloadProgress(
    val operationId: DownloadOperationId,
    val digestPrefix: String,
    val sourceHost: String,
    val stage: DownloadStage,
    val bytesDownloaded: Long,
    val expectedBytes: Long,
    val attempt: Int,
)

fun interface DownloadProgressObserver {
    fun onProgress(progress: DownloadProgress)
}

fun interface DownloadCancellationToken {
    fun isCancelled(): Boolean
}

object NeverCancelled : DownloadCancellationToken {
    override fun isCancelled(): Boolean = false
}

sealed interface ModelDownloadResult {
    val operationId: DownloadOperationId

    data class Success(
        override val operationId: DownloadOperationId,
        val handle: VerifiedDownloadHandle,
        val digest: ModelDigest,
        val sizeBytes: Long,
        val sourceHost: String,
        val deduplicated: Boolean,
    ) : ModelDownloadResult

    data class AlreadyRunning(override val operationId: DownloadOperationId, val digest: ModelDigest) : ModelDownloadResult

    data class Cancelled(override val operationId: DownloadOperationId, val failure: DownloadFailure) : ModelDownloadResult

    data class Failure(override val operationId: DownloadOperationId, val failure: DownloadFailure) : ModelDownloadResult
}

data class DownloadFailure(val code: DownloadFailureCode, val retryable: Boolean, val detail: String)

enum class DownloadFailureCode {
    INVALID_DESCRIPTOR,
    SOURCE_URL_REJECTED,
    SOURCE_ADDRESS_REJECTED,
    INSUFFICIENT_STORAGE,
    DOWNLOAD_HTTP_ERROR,
    DOWNLOAD_REDIRECT_LIMIT,
    DOWNLOAD_REDIRECT_INVALID,
    DOWNLOAD_SIZE_EXCEEDED,
    DOWNLOAD_SIZE_MISMATCH,
    DOWNLOAD_CANCELLED,
    NETWORK_FAILURE,
    CONTENT_ENCODING_UNSUPPORTED,
    SHA256_MISMATCH,
    TEMP_STORAGE_FAILURE,
    VERIFIED_STORAGE_FAILURE,
    INTERNAL_FAILURE,
}

data class InterruptedDownload(
    val operationId: DownloadOperationId,
    val digest: ModelDigest,
    val expectedBytes: Long,
    val sourceHost: String,
    val startedAtEpochMs: Long,
)

data class DownloadCleanupReport(val interrupted: List<InterruptedDownload>, val orphanFilesDeleted: Int, val cleanupFailures: Int)

fun interface DownloadClock {
    fun nowEpochMs(): Long
}

fun interface DownloadSleeper {
    fun sleep(delayMs: Long)
}

fun interface DownloadJitterSource {
    fun nextDouble(): Double
}

fun interface DownloadStorageProbe {
    fun usableBytes(directory: java.io.File): Long
}
