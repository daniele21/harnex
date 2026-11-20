package io.github.daniele21.localllm.externalembedded

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exact-artifact, app-private model ownership. No Harnex GGUF model catalog changes.
 * All methods are blocking: invoke on the SpeechInputController worker only.
 */
internal class WhisperModelStore(context: Context) {
    private val directory = File(context.filesDir, "voice-models")
    private val target = File(directory, MODEL_FILE)

    fun verifiedModel(): File? = target.takeIf { verified(it) }

    fun ensureReady(cancelled: AtomicBoolean, progress: (String) -> Unit): File {
        verifiedModel()?.let { return it }
        check(!cancelled.get()) { "Voice preparation cancelled" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create voice model directory" }
        val temp = File(directory, "$MODEL_FILE.partial")
        temp.delete()
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Voice download failed: HTTP ${connection.responseCode}"
            }
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            progress("Downloading Whisper Small Q5 · 0 MB")
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    while (true) {
                        check(!cancelled.get()) { "Voice download cancelled" }
                        val length = input.read(buffer)
                        if (length < 0) break
                        total += length
                        check(total <= MODEL_BYTES) { "Voice model exceeds expected size" }
                        output.write(buffer, 0, length)
                        if (total % (4L * 1024 * 1024) < buffer.size) {
                            progress("Downloading Whisper Small Q5 · ${total / (1024 * 1024)} / 190 MB")
                        }
                    }
                    output.fd.sync()
                }
            }
            progress("Verifying Whisper Small Q5…")
            check(verified(temp)) { "Whisper model SHA-256 verification failed" }
            // The verified target is published only after transfer + checksum success.
            check(temp.renameTo(target)) { "Cannot install verified voice model" }
            return target
        } finally {
            connection.disconnect()
            temp.delete()
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
