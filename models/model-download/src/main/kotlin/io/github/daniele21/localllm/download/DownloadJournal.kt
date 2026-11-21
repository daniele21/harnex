package io.github.daniele21.localllm.download

import io.github.daniele21.localllm.contracts.ModelDigest
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class DownloadJournalRecord(
    val operationId: DownloadOperationId,
    val digest: ModelDigest,
    val expectedBytes: Long,
    val sourceHost: String,
    val partialFileName: String,
    val startedAtEpochMs: Long,
    val attempt: Int,
)

internal class DownloadJournal(private val operationsDirectory: File, private val partialsDirectory: File) {
    fun write(record: DownloadJournalRecord) {
        ensureDirectories()
        val target = recordFile(record.operationId)
        val staging = File.createTempFile("journal-", ".tmp", operationsDirectory)
        try {
            FileOutputStream(staging).use { output ->
                Properties().apply {
                    setProperty(KEY_OPERATION_ID, record.operationId.value)
                    setProperty(KEY_DIGEST, record.digest.sha256)
                    setProperty(KEY_EXPECTED_BYTES, record.expectedBytes.toString())
                    setProperty(KEY_SOURCE_HOST, record.sourceHost)
                    setProperty(KEY_PARTIAL_FILE, record.partialFileName)
                    setProperty(KEY_STARTED_AT, record.startedAtEpochMs.toString())
                    setProperty(KEY_ATTEMPT, record.attempt.toString())
                }.store(output, null)
                output.fd.sync()
            }
            atomicReplace(staging, target)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    fun remove(operationId: DownloadOperationId): Boolean {
        val file = recordFile(operationId)
        return !file.exists() || file.delete()
    }

    fun recoverAndPurge(): List<InterruptedDownload> {
        ensureDirectories()
        return operationsDirectory.listFiles { file -> file.isFile && file.extension == JOURNAL_EXTENSION }
            .orEmpty()
            .mapNotNull(::recoverRecord)
    }

    private fun recoverRecord(file: File): InterruptedDownload? {
        val record = runCatching { read(file) }.getOrNull()
        if (record != null) {
            // New digest-bound .resume files are retained for byte-range continuation.
            // Legacy per-operation .part files still follow their purge-on-recovery contract.
            if (!record.partialFileName.endsWith(".resume")) {
                safePartial(record.partialFileName)?.delete()
            }
        }
        file.delete()
        return record?.let {
            InterruptedDownload(
                operationId = it.operationId,
                digest = it.digest,
                expectedBytes = it.expectedBytes,
                sourceHost = it.sourceHost,
                startedAtEpochMs = it.startedAtEpochMs,
            )
        }
    }

    private fun read(file: File): DownloadJournalRecord {
        val properties = Properties()
        FileInputStream(file).use { input -> properties.load(input) }
        val operationId = required(properties, KEY_OPERATION_ID)
        val digest = required(properties, KEY_DIGEST)
        val expectedBytes = required(properties, KEY_EXPECTED_BYTES).toLong()
        val sourceHost = required(properties, KEY_SOURCE_HOST)
        val partialFile = required(properties, KEY_PARTIAL_FILE)
        val startedAt = required(properties, KEY_STARTED_AT).toLong()
        val attempt = required(properties, KEY_ATTEMPT).toInt()
        require(operationId.matches(SAFE_NAME))
        require(digest.matches(SHA_256))
        require(expectedBytes > 0)
        require(sourceHost.matches(SAFE_HOST))
        require(partialFile.matches(SAFE_NAME))
        require(startedAt >= 0)
        require(attempt > 0)
        return DownloadJournalRecord(
            operationId = DownloadOperationId(operationId),
            digest = ModelDigest(digest),
            expectedBytes = expectedBytes,
            sourceHost = sourceHost,
            partialFileName = partialFile,
            startedAtEpochMs = startedAt,
            attempt = attempt,
        )
    }

    private fun safePartial(fileName: String): File? {
        if (!fileName.matches(SAFE_NAME)) return null
        val candidate = File(partialsDirectory, fileName)
        return candidate.takeIf { it.parentFile?.canonicalFile == partialsDirectory.canonicalFile }
    }

    private fun ensureDirectories() {
        if (!operationsDirectory.exists() && !operationsDirectory.mkdirs()) {
            throw IOException("Unable to create operation journal directory")
        }
        if (!partialsDirectory.exists() && !partialsDirectory.mkdirs()) {
            throw IOException("Unable to create partial download directory")
        }
    }

    private fun recordFile(operationId: DownloadOperationId): File {
        require(operationId.value.matches(SAFE_NAME))
        return File(operationsDirectory, "${operationId.value}.$JOURNAL_EXTENSION")
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val JOURNAL_EXTENSION = "properties"
        const val KEY_OPERATION_ID = "operationId"
        const val KEY_DIGEST = "digest"
        const val KEY_EXPECTED_BYTES = "expectedBytes"
        const val KEY_SOURCE_HOST = "sourceHost"
        const val KEY_PARTIAL_FILE = "partialFile"
        const val KEY_STARTED_AT = "startedAtEpochMs"
        const val KEY_ATTEMPT = "attempt"
        val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,160}$")
        val SAFE_HOST = Regex("^[a-z0-9.-]{1,253}$")
        val SHA_256 = Regex("^[0-9a-f]{64}$")

        fun required(properties: Properties, key: String): String =
            requireNotNull(properties.getProperty(key)) { "Missing journal property: $key" }
    }
}
