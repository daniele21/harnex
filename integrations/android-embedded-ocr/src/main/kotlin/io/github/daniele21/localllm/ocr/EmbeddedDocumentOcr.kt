package io.github.daniele21.localllm.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Coordinates refer to the EXIF-normalized input bitmap, not a PDF or camera original. */
data class OcrRegion(val id: String, val text: String, val left: Double, val top: Double, val right: Double, val bottom: Double) {
    override fun toString(): String = "OcrRegion(<redacted>)"
}

data class OcrPage(val text: String, val regions: List<OcrRegion>) {
    override fun toString(): String = "OcrPage(regions=${regions.size}, text=<redacted>)"
}

/**
 * Optional bundled Latin OCR. No LLM, projector, network request or independent
 * inference scheduler. Call with an oriented, EXIF-stripped compressed page.
 * Cancellation discards late output; native ML Kit work may finish afterwards.
 * A second recognition is rejected until that work really finishes.
 */
class EmbeddedDocumentOcr {
    private val running = AtomicBoolean(false)

    suspend fun recognize(jpeg: ByteArray): OcrPage = withContext(Dispatchers.Default) {
        require(jpeg.size in 1..2 * 1024 * 1024)
        check(running.compareAndSet(false, true)) { "OCR already active" }
        val bitmap = runCatching { decodeOcrBitmap(jpeg) }.onFailure { running.set(false) }.getOrThrow()
        recognizeBitmap(bitmap)
    }

    /** Keep the gate held after cancellation until ML Kit's task actually finishes. */
    private suspend fun recognizeBitmap(bitmap: Bitmap): OcrPage = suspendCancellableCoroutine { continuation ->
        val client = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }.getOrElse { error ->
            bitmap.recycle()
            running.set(false)
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                try {
                    client.close()
                } finally {
                    bitmap.recycle()
                    running.set(false)
                }
            }
        }
        runCatching {
            client.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                try {
                    if (continuation.isActive) {
                        runCatching { continuation.resume(normalizeOcrResult(task.result, bitmap)) }
                            .onFailure { error ->
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                    }
                } finally {
                    release()
                }
            }
        }.onFailure { error ->
            release()
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}

/** Source pixels are limited before allocation and never interpreted as a model image. */
private fun decodeOcrBitmap(jpeg: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    require(bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048) {
        "Unsupported OCR page dimensions"
    }
    return requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Invalid OCR page" }
}

/** Bounding boxes use the exact decoded bitmap's dimensions, after EXIF normalization upstream. */
private fun normalizeOcrResult(result: Text, bitmap: Bitmap): OcrPage {
    val text = result.text.trim()
    require(text.isNotBlank() && text.length <= 12_000 && '\u0000' !in text) {
        "OCR text unavailable or outside bounds"
    }
    val width = bitmap.width.toDouble()
    val height = bitmap.height.toDouble()
    val regions = result.textBlocks.flatMap { it.lines }.mapIndexedNotNull { index, line ->
        val rect = line.boundingBox ?: return@mapIndexedNotNull null
        val left = (rect.left / width).coerceIn(0.0, 1.0)
        val top = (rect.top / height).coerceIn(0.0, 1.0)
        val right = (rect.right / width).coerceIn(0.0, 1.0)
        val bottom = (rect.bottom / height).coerceIn(0.0, 1.0)
        if (left >= right || top >= bottom || line.text.isBlank()) {
            null
        } else {
            OcrRegion("line-" + (index + 1), line.text, left, top, right, bottom)
        }
    }
    return OcrPage(text, regions)
}
