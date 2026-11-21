package io.github.daniele21.localllm.download

import io.github.daniele21.localllm.contracts.ModelDigest
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal data class PublishedDownload(val file: File, val deduplicated: Boolean)

@Suppress("TooManyFunctions")
internal class DownloadFileStore(rootDirectory: File) {
    private val root = rootDirectory.canonicalFile
    val partialsDirectory = childDirectory(PARTIALS_DIRECTORY)
    val operationsDirectory = childDirectory(OPERATIONS_DIRECTORY)
    private val verifiedDirectory = childDirectory(VERIFIED_DIRECTORY)

    init {
        ensureDirectory(root)
        ensureDirectory(partialsDirectory)
        ensureDirectory(operationsDirectory)
        ensureDirectory(verifiedDirectory)
    }

    /**
     * Digest-bound partial survives network errors and process death. No downloaded name
     * or URI is ever used for a filesystem path; verified publication still checks full SHA.
     */
    fun resumablePartial(digest: ModelDigest, expectedBytes: Long): File {
        val candidate = resumeFile(digest)
        if (candidate.exists()) {
            if (!candidate.isFile || Files.isSymbolicLink(candidate.toPath())) {
                throw IOException("Resume path is not an owned regular file")
            }
            if (candidate.length() > expectedBytes) {
                if (!candidate.delete()) throw IOException("Unable to reset oversized resume file")
            }
        }
        return candidate
    }

    fun resumableLength(digest: ModelDigest, expectedBytes: Long): Long =
        resumablePartial(digest, expectedBytes).takeIf(File::exists)?.length() ?: 0L

    fun discardResumable(digest: ModelDigest): Boolean = resumeFile(digest).let { !it.exists() || it.delete() }

    fun cleanupAbandonedResumes(cutoffEpochMs: Long): Pair<Int, Int> {
        var deleted = 0
        var failures = 0
        partialsDirectory.listFiles { candidate ->
            candidate.isFile && RESUME_FILENAME.matches(candidate.name) &&
                candidate.lastModified() <= cutoffEpochMs
        }.orEmpty().forEach { candidate ->
            if (candidate.delete()) deleted++ else failures++
        }
        return deleted to failures
    }

    private fun resumeFile(digest: ModelDigest): File {
        require(digest.sha256.matches(SHA_256))
        return File(partialsDirectory, "resume-${digest.sha256}.resume")
    }

    fun createPartial(): File = File.createTempFile(PARTIAL_PREFIX, PARTIAL_SUFFIX, partialsDirectory)

    fun findVerified(digest: ModelDigest, expectedBytes: Long): File? {
        val file = verifiedFile(digest)
        if (!file.isFile || file.length() != expectedBytes) return null
        return file.takeIf { sha256(it) == digest.sha256 }
    }

    fun publish(partial: File, digest: ModelDigest, expectedBytes: Long): PublishedDownload {
        requireControlledPartial(partial)
        val destination = verifiedFile(digest)
        val existing = findVerified(digest, expectedBytes)
        if (existing != null) {
            partial.delete()
            return PublishedDownload(existing, deduplicated = true)
        }
        if (destination.exists() && !destination.delete()) {
            throw IOException("Unable to replace invalid verified download")
        }
        atomicReplace(partial, destination)
        if (destination.length() != expectedBytes || sha256(destination) != digest.sha256) {
            destination.delete()
            throw IOException("Published verified download failed integrity recheck")
        }
        return PublishedDownload(destination, deduplicated = false)
    }

    fun cleanupOrphanPartials(cutoffEpochMs: Long): Pair<Int, Int> {
        var deleted = 0
        var failures = 0
        partialsDirectory.listFiles { file -> file.isFile && file.name.endsWith(PARTIAL_SUFFIX) }
            .orEmpty()
            .filter { it.lastModified() <= cutoffEpochMs }
            .forEach { file ->
                if (file.delete()) deleted += 1 else failures += 1
            }
        return deleted to failures
    }

    internal fun resolve(handle: VerifiedDownloadHandle): File? {
        val digest = handle.value.takeIf { it.matches(SHA_256) } ?: return null
        return verifiedFile(ModelDigest(digest)).takeIf(File::isFile)
    }

    fun discard(handle: VerifiedDownloadHandle): Boolean {
        val file = resolve(handle) ?: return false
        return file.delete()
    }

    private fun verifiedFile(digest: ModelDigest): File {
        require(digest.sha256.matches(SHA_256))
        return File(verifiedDirectory, "${digest.sha256}$VERIFIED_SUFFIX")
    }

    private fun childDirectory(name: String): File {
        val child = File(root, name).canonicalFile
        require(child.parentFile == root) { "Download directory escaped its controlled root" }
        return child
    }

    private fun requireControlledPartial(file: File) {
        val canonical = file.canonicalFile
        require(canonical.parentFile == partialsDirectory)
        require(canonical.name.endsWith(PARTIAL_SUFFIX) || RESUME_FILENAME.matches(canonical.name))
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory) throw IOException("Download path is not a directory")
        } else if (!directory.mkdirs()) {
            throw IOException("Unable to create download directory")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val buffer = ByteArray(HASH_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
        const val PARTIALS_DIRECTORY = "partials"
        const val OPERATIONS_DIRECTORY = "operations"
        const val VERIFIED_DIRECTORY = "verified"
        const val PARTIAL_PREFIX = "model-"
        const val PARTIAL_SUFFIX = ".part"
        const val VERIFIED_SUFFIX = ".verified"
        const val SHA_256_ALGORITHM = "SHA-256"
        const val HASH_BUFFER_SIZE = 65_536
        val SHA_256 = Regex("^[0-9a-f]{64}$")
        val RESUME_FILENAME = Regex("^resume-[0-9a-f]{64}\\.resume$")
    }
}
