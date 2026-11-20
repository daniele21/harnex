package io.github.daniele21.localllm.embedded

import android.content.Context
import io.github.daniele21.localllm.catalog.CatalogGgufArtifact
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.download.AllowedSourceHost
import io.github.daniele21.localllm.download.AllowlistedHttpsSourcePolicy
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.FileSystemVerifiedDownloadAccess
import io.github.daniele21.localllm.download.ModelDownloadRequest
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.download.SecureModelDownloader
import io.github.daniele21.localllm.download.VerifiedDownloadCopyRequest
import io.github.daniele21.localllm.download.VerifiedDownloadCopyResult
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Experimental 2B-only image projector. The fixed source is never a consumer-supplied URL.
 * Reuses the SDK's secure download and copy verification; the backend opens only this private file.
 */
internal class EmbeddedVisionProjector(context: Context) {
    private val directory = File(context.noBackupFilesDir, "harnex-embedded-vision")
    val file = File(directory, "mmproj-f16.gguf")
    private val downloadsDirectory = File(directory, "verified-downloads")
    private val downloader = SecureModelDownloader(
        rootDirectory = downloadsDirectory,
        sourcePolicy = AllowlistedHttpsSourcePolicy(
            setOf(
                AllowedSourceHost("huggingface.co", includeSubdomains = true),
                AllowedSourceHost("hf.co", includeSubdomains = true),
            ),
        ),
    )
    private val verifiedAccess = FileSystemVerifiedDownloadAccess(downloadsDirectory)

    fun verified(): Boolean = file.isFile && file.length() == PROJECTOR_BYTES && sha256(file) == PROJECTOR_SHA256

    fun ensureReady(cancel: DownloadCancellationToken, progress: DownloadProgressObserver) {
        if (verified()) return
        downloader.recoverInterruptedDownloads()
        check(!cancel.isCancelled()) { "Vision preparation cancelled" }
        val result = downloader.download(ModelDownloadRequest(PROJECTOR, preservePartialOnCancel = true), progress, cancel)
        val handle = when (result) {
            is ModelDownloadResult.Success -> result.handle
            is ModelDownloadResult.Cancelled -> error("Vision download cancelled")
            is ModelDownloadResult.Failure -> error("Vision download failed: ${result.failure.code}")
            is ModelDownloadResult.AlreadyRunning -> error("Vision download already running")
        }
        check(!cancel.isCancelled()) { "Vision preparation cancelled" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create private vision directory" }
        val temporary = File.createTempFile("projector-", ".part", directory)
        try {
            val copied = verifiedAccess.copyTo(
                VerifiedDownloadCopyRequest(handle, ModelDigest(PROJECTOR_SHA256), PROJECTOR_BYTES),
                temporary,
            )
            check(copied is VerifiedDownloadCopyResult.Success) {
                "Vision projector failed integrity verification"
            }
            check(!cancel.isCancelled()) { "Vision preparation cancelled" }
            try {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(verified()) { "Vision projector integrity verification failed after installation" }
            verifiedAccess.discard(handle)
        } finally {
            temporary.delete()
        }
    }

    private fun sha256(value: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        value.inputStream().buffered().use { stream ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val length = stream.read(bytes)
                if (length < 0) break
                digest.update(bytes, 0, length)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val PROJECTOR_BYTES = 668_227_264L
        const val PROJECTOR_SHA256 = "7035e9cb8d7c6a9681d07eef9a364783e86ea4cd73faab2eabb4f43a101830c7"
        val PROJECTOR = CatalogGgufArtifact(
            digest = ModelDigest(PROJECTOR_SHA256),
            sizeBytes = PROJECTOR_BYTES,
            downloadUri = URI(
                "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/" +
                    "1c466474d208da1a7c4b8cb87ebcdac78f160e34/mmproj-F16.gguf?download=true",
            ),
            architecture = "qwen35",
            quantization = "F16",
            fileName = "mmproj-F16.gguf",
        )
    }
}
