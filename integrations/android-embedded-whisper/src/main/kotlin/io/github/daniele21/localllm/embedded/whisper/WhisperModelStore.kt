package io.github.daniele21.localllm.embedded.whisper

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Exact-artifact, app-private model ownership. No Harnex GGUF model catalog changes.
 * All methods are blocking: invoke on the SDK worker only.
 */
internal class WhisperModelStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "harnex-whisper")
    private val target = File(directory, MODEL_FILE)

    fun verifiedModel(): File? = target.takeIf { verified(it) }

    fun ensureReady(cancelled: WhisperCancellation, progress: (Long, Long) -> Unit): File {
        verifiedModel()?.let { return it }
        cancelled.check()
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create voice model directory" }
        val temp = File.createTempFile("whisper-", ".partial", directory)
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Voice download failed: HTTP ${connection.responseCode}"
            }
            progress(0, MODEL_BYTES)
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    copyModelBytes(input, output, cancelled, progress)
                    output.fd.sync()
                }
            }
            check(verified(temp)) { "Whisper model SHA-256 verification failed" }
            cancelled.check()
            progress(MODEL_BYTES, MODEL_BYTES)
            cancelled.check()
            // The verified target is published only after transfer + checksum success.
            check(temp.renameTo(target)) { "Cannot install verified voice model" }
            return target
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun copyModelBytes(
        input: InputStream,
        output: FileOutputStream,
        cancelled: WhisperCancellation,
        progress: (Long, Long) -> Unit,
    ) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            cancelled.check()
            val length = input.read(buffer)
            if (length < 0) return
            total += length
            check(total <= MODEL_BYTES) { "Voice model exceeds expected size" }
            output.write(buffer, 0, length)
            if (total % (4L * 1024 * 1024) < buffer.size) {
                progress(total, MODEL_BYTES)
            }
        }
    }

    private fun verified(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual == MODEL_SHA256
    }

    private companion object {
        const val MODEL_FILE = "ggml-small-q5_1.bin"
        const val MODEL_BYTES = 190_085_487L
        const val MODEL_SHA256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        const val MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small-q5_1.bin"
    }
}
