package io.github.daniele21.localllm.download

import io.github.daniele21.localllm.catalog.CatalogGgufArtifact
import io.github.daniele21.localllm.contracts.ModelDigest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.random.Random

@Suppress("LongParameterList", "TooManyFunctions")
class SecureModelDownloader(
    rootDirectory: File,
    private val sourcePolicy: ArtifactSourcePolicy,
    private val transport: DownloadTransport = HttpUrlConnectionDownloadTransport(),
    private val networkPolicy: ArtifactNetworkPolicy = PublicNetworkAddressPolicy(),
    private val configuration: DownloadConfiguration = DownloadConfiguration(),
    private val clock: DownloadClock = DownloadClock(System::currentTimeMillis),
    private val sleeper: DownloadSleeper = DownloadSleeper(Thread::sleep),
    private val jitterSource: DownloadJitterSource = DownloadJitterSource { Random.nextDouble() },
    private val storageProbe: DownloadStorageProbe = DownloadStorageProbe(File::getUsableSpace),
) {
    private val fileStore = DownloadFileStore(rootDirectory)
    private val journal = DownloadJournal(fileStore.operationsDirectory, fileStore.partialsDirectory)
    private val activeOperations = concurrentOperations
    private val controlledRoot = fileStore.partialsDirectory.canonicalPath

    @Suppress("ReturnCount", "SwallowedException")
    fun download(
        request: ModelDownloadRequest,
        observer: DownloadProgressObserver = DownloadProgressObserver {},
        cancellationToken: DownloadCancellationToken = NeverCancelled,
    ): ModelDownloadResult {
        val operationId = DownloadOperationId(UUID.randomUUID().toString())
        val descriptor = validateDescriptor(request.artifact) ?: return invalidDescriptor(operationId)
        val source = sourcePolicy.validate(descriptor.downloadUri)
        if (source is SourcePolicyResult.Rejected) {
            return failure(operationId, DownloadFailureCode.SOURCE_URL_REJECTED, false, source.reason.name)
        }
        source as SourcePolicyResult.Allowed
        if (cancellationToken.isCancelled()) {
            return cancelled(operationId)
        }

        try {
            fileStore.findVerified(descriptor.digest, descriptor.sizeBytes)
        } catch (error: IOException) {
            return failure(
                operationId,
                DownloadFailureCode.VERIFIED_STORAGE_FAILURE,
                false,
                error.javaClass.simpleName,
            )
        }?.let {
            emitTerminal(observer, operationId, descriptor, source.normalizedHost, DownloadStage.COMPLETED, 1)
            return success(operationId, descriptor, source.normalizedHost, deduplicated = true)
        }

        val activeKey = "$controlledRoot:${descriptor.digest.sha256}"
        val existing = activeOperations.putIfAbsent(activeKey, operationId)
        if (existing != null) return ModelDownloadResult.AlreadyRunning(existing, descriptor.digest)

        return try {
            executeDownload(operationId, descriptor, source, observer, cancellationToken, request.preservePartialOnCancel)
        } finally {
            activeOperations.remove(activeKey, operationId)
        }
    }

    fun recoverInterruptedDownloads(): DownloadCleanupReport {
        val interrupted = journal.recoverAndPurge()
        val cutoff = safeSubtract(clock.nowEpochMs(), configuration.orphanRetentionMs)
        val (deleted, failures) = fileStore.cleanupOrphanPartials(cutoff)
        val resumeCutoff = safeSubtract(clock.nowEpochMs(), configuration.resumeRetentionMs)
        val (resumesDeleted, resumeFailures) = fileStore.cleanupAbandonedResumes(resumeCutoff)
        return DownloadCleanupReport(interrupted, deleted + resumesDeleted, failures + resumeFailures)
    }

    internal fun resolveVerifiedFile(handle: VerifiedDownloadHandle): File? = fileStore.resolve(handle)

    fun discardVerifiedDownload(handle: VerifiedDownloadHandle): Boolean = fileStore.discard(handle)

    /** Explicit user reset; an interrupted system download otherwise keeps resumable bytes. */
    fun discardIncompleteDownload(digest: ModelDigest): Boolean = fileStore.discardResumable(digest)

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "LoopWithTooManyJumpStatements",
        "ReturnCount",
        "SwallowedException",
        "TooGenericExceptionCaught",
    )
    private fun executeDownload(
        operationId: DownloadOperationId,
        artifact: CatalogGgufArtifact,
        initialSource: SourcePolicyResult.Allowed,
        observer: DownloadProgressObserver,
        cancellationToken: DownloadCancellationToken,
        preservePartialOnCancel: Boolean,
    ): ModelDownloadResult {
        val reporter = ProgressReporter(operationId, artifact, initialSource.normalizedHost, observer)
        reporter.emit(DownloadStage.PREPARING, 0, 1, force = true)
        preflightStorage(artifact, operationId)?.let {
            reporter.emit(DownloadStage.FAILED, 0, 1, force = true)
            return ModelDownloadResult.Failure(operationId, it)
        }

        var lastFailure: DownloadFailure? = null
        for (attempt in 1..configuration.retryPolicy.maxAttempts) {
            var partial: File? = null
            try {
                ensureNotCancelled(cancellationToken)
                partial = fileStore.resumablePartial(artifact.digest, artifact.sizeBytes)
                writeJournal(
                    DownloadJournalRecord(
                        operationId = operationId,
                        digest = artifact.digest,
                        expectedBytes = artifact.sizeBytes,
                        sourceHost = initialSource.normalizedHost,
                        partialFileName = partial.name,
                        startedAtEpochMs = clock.nowEpochMs(),
                        attempt = attempt,
                    ),
                )
                val existingBytes = partial.length()
                reporter.emit(DownloadStage.CONNECTING, existingBytes, attempt, force = true)
                var opened = openFollowingRedirects(initialSource, cancellationToken, existingBytes)
                var offset = if (existingBytes > 0 && validPartialResponse(opened.response, artifact, existingBytes)) {
                    existingBytes
                } else {
                    0L
                }
                if (existingBytes > 0 && opened.response.statusCode != HTTP_OK && offset == 0L) {
                    // Invalid/missing Content-Range (including 416): discard, then request full body.
                    opened.response.close()
                    if (!partial.delete()) throw IOException("Unable to reset unsafe resume")
                    opened = openFollowingRedirects(initialSource, cancellationToken, 0L)
                }
                opened.response.use { response ->
                    validateResponse(response, artifact, offset)
                    reporter.sourceHost = opened.source.normalizedHost
                    val transfer = transfer(response, partial, artifact, reporter, cancellationToken, attempt, offset)
                    reporter.emit(DownloadStage.VERIFYING, transfer.bytes, attempt, force = true)
                    verifyTransfer(artifact, transfer)
                }
                ensureNotCancelled(cancellationToken)
                val published = try {
                    fileStore.publish(partial, artifact.digest, artifact.sizeBytes)
                } catch (error: IOException) {
                    throw downloadError(
                        DownloadFailureCode.VERIFIED_STORAGE_FAILURE,
                        false,
                        error.javaClass.simpleName,
                    )
                }
                journal.remove(operationId)
                reporter.emit(DownloadStage.COMPLETED, artifact.sizeBytes, attempt, force = true)
                return success(operationId, artifact, reporter.sourceHost, published.deduplicated)
            } catch (error: DownloadException) {
                journal.remove(operationId)
                lastFailure = error.failure
                if (error.failure.code == DownloadFailureCode.DOWNLOAD_CANCELLED) {
                    if (!preservePartialOnCancel) partial?.delete()
                    reporter.emit(DownloadStage.CANCELLED, reporter.lastBytes, attempt, force = true)
                    return ModelDownloadResult.Cancelled(operationId, error.failure)
                }
                if (!error.failure.retryable) partial?.delete()
                if (!error.failure.retryable || attempt == configuration.retryPolicy.maxAttempts) break
                reporter.emit(DownloadStage.RETRY_WAIT, reporter.lastBytes, attempt, force = true)
                retryDelay(attempt, cancellationToken)?.let { cancellation ->
                    if (!preservePartialOnCancel) partial?.delete()
                    reporter.emit(DownloadStage.CANCELLED, reporter.lastBytes, attempt, force = true)
                    return ModelDownloadResult.Cancelled(operationId, cancellation)
                }
            } catch (error: IOException) {
                journal.remove(operationId)
                lastFailure = DownloadFailure(
                    DownloadFailureCode.NETWORK_FAILURE,
                    retryable = true,
                    detail = error.javaClass.simpleName,
                )
                if (attempt == configuration.retryPolicy.maxAttempts) break
                reporter.emit(DownloadStage.RETRY_WAIT, reporter.lastBytes, attempt, force = true)
                retryDelay(attempt, cancellationToken)?.let { cancellation ->
                    if (!preservePartialOnCancel) partial?.delete()
                    reporter.emit(DownloadStage.CANCELLED, reporter.lastBytes, attempt, force = true)
                    return ModelDownloadResult.Cancelled(operationId, cancellation)
                }
            } catch (error: RuntimeException) {
                partial?.delete()
                journal.remove(operationId)
                lastFailure = DownloadFailure(
                    DownloadFailureCode.INTERNAL_FAILURE,
                    retryable = false,
                    detail = error.javaClass.simpleName,
                )
                break
            }
        }
        reporter.emit(DownloadStage.FAILED, reporter.lastBytes, configuration.retryPolicy.maxAttempts, force = true)
        return ModelDownloadResult.Failure(
            operationId,
            lastFailure ?: DownloadFailure(DownloadFailureCode.INTERNAL_FAILURE, false, "unknown"),
        )
    }

    private fun validateDescriptor(artifact: CatalogGgufArtifact): CatalogGgufArtifact? {
        val digest = artifact.digest.sha256.lowercase()
        val valid = digest.matches(SHA_256) &&
            artifact.sizeBytes > 0 &&
            artifact.sizeBytes <= configuration.maxArtifactBytes &&
            artifact.downloadUri.isAbsolute
        if (!valid) return null
        return artifact.copy(digest = ModelDigest(digest))
    }

    private fun preflightStorage(artifact: CatalogGgufArtifact, operationId: DownloadOperationId): DownloadFailure? {
        val remaining = artifact.sizeBytes - fileStore.resumableLength(artifact.digest, artifact.sizeBytes)
        val required = safeAdd(remaining, configuration.storageHeadroomBytes)
            ?: return DownloadFailure(DownloadFailureCode.INVALID_DESCRIPTOR, false, "storage-overflow")
        val available = storageProbe.usableBytes(fileStore.partialsDirectory)
        return if (available < required) {
            DownloadFailure(
                DownloadFailureCode.INSUFFICIENT_STORAGE,
                retryable = false,
                detail = "required=$required available=$available operation=${operationId.value.take(8)}",
            )
        } else {
            null
        }
    }

    private fun openFollowingRedirects(
        initialSource: SourcePolicyResult.Allowed,
        cancellationToken: DownloadCancellationToken,
        rangeStartBytes: Long = 0L,
    ): OpenedResponse {
        var source = initialSource
        var redirects = 0
        while (true) {
            ensureNotCancelled(cancellationToken)
            validateNetworkAddress(source.normalizedHost)
            val response = transport.execute(
                DownloadTransportRequest(
                    uri = source.uri,
                    connectTimeoutMs = configuration.connectTimeoutMs,
                    readTimeoutMs = configuration.readTimeoutMs,
                    rangeStartBytes = rangeStartBytes,
                ),
            )
            if (response.statusCode !in REDIRECT_CODES) return OpenedResponse(source, response)
            val location = response.redirectLocation
            response.close()
            if (redirects >= configuration.maxRedirects) {
                throw downloadError(DownloadFailureCode.DOWNLOAD_REDIRECT_LIMIT, false, "redirect-limit")
            }
            val redirected = parseRedirect(source.uri, location)
            source = when (val result = sourcePolicy.validate(redirected)) {
                is SourcePolicyResult.Allowed -> result

                is SourcePolicyResult.Rejected -> throw downloadError(
                    DownloadFailureCode.DOWNLOAD_REDIRECT_INVALID,
                    false,
                    result.reason.name,
                )
            }
            redirects += 1
        }
    }

    private fun validateNetworkAddress(host: String) {
        when (val result = networkPolicy.validate(host)) {
            NetworkAddressPolicyResult.Allowed -> Unit

            is NetworkAddressPolicyResult.Rejected -> throw downloadError(
                DownloadFailureCode.SOURCE_ADDRESS_REJECTED,
                retryable = false,
                detail = result.reason.name,
            )
        }
    }

    private fun parseRedirect(current: URI, location: String?): URI {
        if (location.isNullOrBlank()) {
            throw downloadError(DownloadFailureCode.DOWNLOAD_REDIRECT_INVALID, false, "missing-location")
        }
        return try {
            current.resolve(URI(location))
        } catch (_: java.net.URISyntaxException) {
            throw downloadError(DownloadFailureCode.DOWNLOAD_REDIRECT_INVALID, false, "invalid-location")
        }
    }

    private fun validPartialResponse(response: DownloadTransportResponse, artifact: CatalogGgufArtifact, offset: Long): Boolean {
        if (response.statusCode != HTTP_PARTIAL) return false
        val match = CONTENT_RANGE.matchEntire(response.contentRange.orEmpty()) ?: return false
        val start = match.groupValues[1].toLongOrNull() ?: return false
        val end = match.groupValues[2].toLongOrNull() ?: return false
        val total = match.groupValues[3].toLongOrNull() ?: return false
        return start == offset && end >= start && end < total && total == artifact.sizeBytes &&
            (response.contentLengthBytes == null || response.contentLengthBytes == end - start + 1)
    }

    @Suppress("ThrowsCount")
    private fun validateResponse(response: DownloadTransportResponse, artifact: CatalogGgufArtifact, offset: Long) {
        if (response.statusCode != if (offset > 0L) HTTP_PARTIAL else HTTP_OK) {
            val retryable = response.statusCode in RETRYABLE_HTTP_CODES
            throw downloadError(DownloadFailureCode.DOWNLOAD_HTTP_ERROR, retryable, "status=${response.statusCode}")
        }
        val encoding = response.contentEncoding?.trim()?.lowercase()
        if (!encoding.isNullOrEmpty() && encoding != IDENTITY_ENCODING) {
            throw downloadError(DownloadFailureCode.CONTENT_ENCODING_UNSUPPORTED, false, encoding)
        }
        val remaining = artifact.sizeBytes - offset
        response.contentLengthBytes?.let { contentLength ->
            if (contentLength > configuration.maxArtifactBytes || contentLength > remaining) {
                throw downloadError(DownloadFailureCode.DOWNLOAD_SIZE_EXCEEDED, false, "content-length")
            }
            // A 206 response may represent a shorter bounded chunk, which will retry on truncation.
            if (offset == 0L && contentLength != artifact.sizeBytes) {
                throw downloadError(DownloadFailureCode.DOWNLOAD_SIZE_MISMATCH, false, "content-length")
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements", "NestedBlockDepth", "SwallowedException", "ThrowsCount")
    private fun transfer(
        response: DownloadTransportResponse,
        partial: File,
        artifact: CatalogGgufArtifact,
        reporter: ProgressReporter,
        cancellationToken: DownloadCancellationToken,
        attempt: Int,
        offset: Long,
    ): TransferResult {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val buffer = ByteArray(configuration.bufferSizeBytes)
        var total = offset
        if (offset > 0L) {
            // Re-hash the existing disk bytes before appending; full SHA remains authoritative.
            FileInputStream(partial).use { existing ->
                while (true) {
                    ensureNotCancelled(cancellationToken)
                    val count = existing.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
            }
        }
        reporter.emit(DownloadStage.DOWNLOADING, total, attempt, force = true)
        val input = try {
            response.openBody()
        } catch (error: IOException) {
            throw downloadError(DownloadFailureCode.NETWORK_FAILURE, true, error.javaClass.simpleName)
        }
        input.use { source ->
            val output = try {
                FileOutputStream(partial, offset > 0L)
            } catch (error: IOException) {
                throw downloadError(DownloadFailureCode.TEMP_STORAGE_FAILURE, false, error.javaClass.simpleName)
            }
            output.use { destination ->
                while (true) {
                    ensureNotCancelled(cancellationToken)
                    val count = try {
                        source.read(buffer)
                    } catch (error: IOException) {
                        throw downloadError(DownloadFailureCode.NETWORK_FAILURE, true, error.javaClass.simpleName)
                    }
                    if (count == -1) break
                    if (count == 0) continue
                    if (total > artifact.sizeBytes - count) {
                        throw downloadError(DownloadFailureCode.DOWNLOAD_SIZE_EXCEEDED, false, "stream")
                    }
                    try {
                        destination.write(buffer, 0, count)
                    } catch (error: IOException) {
                        throw downloadError(
                            classifyWriteFailure(artifact.sizeBytes - total),
                            false,
                            error.javaClass.simpleName,
                        )
                    }
                    digest.update(buffer, 0, count)
                    total += count
                    reporter.emit(DownloadStage.DOWNLOADING, total, attempt)
                }
                try {
                    destination.fd.sync()
                } catch (error: IOException) {
                    throw downloadError(DownloadFailureCode.TEMP_STORAGE_FAILURE, false, error.javaClass.simpleName)
                }
            }
        }
        return TransferResult(total, digest.digest().toHex())
    }

    private fun verifyTransfer(artifact: CatalogGgufArtifact, transfer: TransferResult) {
        if (transfer.bytes != artifact.sizeBytes) {
            // An otherwise valid connection can end early without throwing IOException.
            // Keep the digest-bound bytes and retry with Range, never publish incomplete data.
            throw downloadError(DownloadFailureCode.DOWNLOAD_SIZE_MISMATCH, true, "stream")
        }
        if (transfer.sha256 != artifact.digest.sha256) {
            throw downloadError(DownloadFailureCode.SHA256_MISMATCH, false, "digest")
        }
    }

    private fun retryDelay(attempt: Int, cancellationToken: DownloadCancellationToken): DownloadFailure? = try {
        sleepBeforeRetry(attempt, cancellationToken)
        null
    } catch (error: DownloadException) {
        error.failure.takeIf { it.code == DownloadFailureCode.DOWNLOAD_CANCELLED } ?: throw error
    }

    private fun sleepBeforeRetry(attempt: Int, cancellationToken: DownloadCancellationToken) {
        ensureNotCancelled(cancellationToken)
        val policy = configuration.retryPolicy
        val exponential = policy.initialDelayMs.toDouble() * 2.0.pow((attempt - 1).toDouble())
        val capped = exponential.coerceAtMost(policy.maxDelayMs.toDouble())
        val random = jitterSource.nextDouble().coerceIn(0.0, 1.0)
        val jitter = 1.0 + ((random * 2.0) - 1.0) * policy.jitterRatio
        val delay = (capped * jitter).toLong().coerceAtLeast(0L)
        try {
            sleeper.sleep(delay)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw downloadError(DownloadFailureCode.DOWNLOAD_CANCELLED, false, "interrupted")
        }
        ensureNotCancelled(cancellationToken)
    }

    private fun ensureNotCancelled(token: DownloadCancellationToken) {
        if (token.isCancelled()) {
            throw downloadError(DownloadFailureCode.DOWNLOAD_CANCELLED, false, "cancelled")
        }
    }

    @Suppress("SwallowedException")
    private fun createPartial(): File = try {
        fileStore.createPartial()
    } catch (error: IOException) {
        throw downloadError(DownloadFailureCode.TEMP_STORAGE_FAILURE, false, error.javaClass.simpleName)
    }

    @Suppress("SwallowedException")
    private fun writeJournal(record: DownloadJournalRecord) {
        try {
            journal.write(record)
        } catch (error: IOException) {
            throw downloadError(DownloadFailureCode.TEMP_STORAGE_FAILURE, false, error.javaClass.simpleName)
        }
    }

    private fun classifyWriteFailure(remainingBytes: Long): DownloadFailureCode =
        if (storageProbe.usableBytes(fileStore.partialsDirectory) < remainingBytes) {
            DownloadFailureCode.INSUFFICIENT_STORAGE
        } else {
            DownloadFailureCode.TEMP_STORAGE_FAILURE
        }

    private fun success(
        operationId: DownloadOperationId,
        artifact: CatalogGgufArtifact,
        sourceHost: String,
        deduplicated: Boolean,
    ): ModelDownloadResult.Success = ModelDownloadResult.Success(
        operationId = operationId,
        handle = VerifiedDownloadHandle(artifact.digest.sha256),
        digest = artifact.digest,
        sizeBytes = artifact.sizeBytes,
        sourceHost = sourceHost,
        deduplicated = deduplicated,
    )

    private fun invalidDescriptor(operationId: DownloadOperationId): ModelDownloadResult.Failure =
        failure(operationId, DownloadFailureCode.INVALID_DESCRIPTOR, false, "invalid-artifact")

    private fun cancelled(operationId: DownloadOperationId): ModelDownloadResult.Cancelled = ModelDownloadResult.Cancelled(
        operationId,
        DownloadFailure(DownloadFailureCode.DOWNLOAD_CANCELLED, retryable = false, detail = "cancelled"),
    )

    private fun failure(
        operationId: DownloadOperationId,
        code: DownloadFailureCode,
        retryable: Boolean,
        detail: String,
    ): ModelDownloadResult.Failure = ModelDownloadResult.Failure(
        operationId,
        DownloadFailure(code, retryable, detail),
    )

    private fun emitTerminal(
        observer: DownloadProgressObserver,
        operationId: DownloadOperationId,
        artifact: CatalogGgufArtifact,
        host: String,
        stage: DownloadStage,
        attempt: Int,
    ) {
        observer.onProgress(
            DownloadProgress(
                operationId,
                artifact.digest.sha256.take(DIGEST_PREFIX_LENGTH),
                host,
                stage,
                artifact.sizeBytes,
                artifact.sizeBytes,
                attempt,
            ),
        )
    }

    private inner class ProgressReporter(
        private val operationId: DownloadOperationId,
        private val artifact: CatalogGgufArtifact,
        var sourceHost: String,
        private val observer: DownloadProgressObserver,
    ) {
        var lastBytes: Long = 0
            private set
        private var lastEmissionBytes = 0L
        private var lastEmissionAt = Long.MIN_VALUE
        private var lastStage: DownloadStage? = null

        @Suppress("ComplexCondition")
        fun emit(stage: DownloadStage, bytes: Long, attempt: Int, force: Boolean = false) {
            lastBytes = bytes
            val now = clock.nowEpochMs()
            val stageChanged = stage != lastStage
            val enoughBytes = bytes - lastEmissionBytes >= configuration.progressMinBytesDelta
            val enoughTime = lastEmissionAt == Long.MIN_VALUE ||
                now - lastEmissionAt >= configuration.progressMinIntervalMs
            if (!force && !stageChanged && !enoughBytes && !enoughTime) return
            observer.onProgress(
                DownloadProgress(
                    operationId = operationId,
                    digestPrefix = artifact.digest.sha256.take(DIGEST_PREFIX_LENGTH),
                    sourceHost = sourceHost,
                    stage = stage,
                    bytesDownloaded = bytes,
                    expectedBytes = artifact.sizeBytes,
                    attempt = attempt,
                ),
            )
            lastEmissionBytes = bytes
            lastEmissionAt = now
            lastStage = stage
        }
    }

    private data class OpenedResponse(val source: SourcePolicyResult.Allowed, val response: DownloadTransportResponse)

    private data class TransferResult(val bytes: Long, val sha256: String)

    private class DownloadException(val failure: DownloadFailure) : IOException(failure.code.name)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_PARTIAL = 206
        val CONTENT_RANGE = Regex("^bytes ([0-9]+)-([0-9]+)/([0-9]+)$")
        val concurrentOperations = ConcurrentHashMap<String, DownloadOperationId>()
        const val IDENTITY_ENCODING = "identity"
        const val SHA_256_ALGORITHM = "SHA-256"
        const val DIGEST_PREFIX_LENGTH = 12
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)

        fun downloadError(code: DownloadFailureCode, retryable: Boolean, detail: String): DownloadException =
            DownloadException(DownloadFailure(code, retryable, detail))

        fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun safeAdd(left: Long, right: Long): Long? = if (right > Long.MAX_VALUE - left) null else left + right

        fun safeSubtract(left: Long, right: Long): Long = if (right > left) Long.MIN_VALUE else left - right
    }
}
