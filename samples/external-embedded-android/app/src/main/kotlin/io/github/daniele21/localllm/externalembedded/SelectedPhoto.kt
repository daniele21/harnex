package io.github.daniele21.localllm.externalembedded

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/** Transient photo data; no URI permission retention and no private image files. */
internal data class SelectedPhoto(val jpegBytes: ByteArray, val preview: Bitmap)

internal object PhotoDecoder {
    fun decode(resolver: ContentResolver, uri: Uri): SelectedPhoto {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode returns null for the bitmap by design.
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val width = bounds.outWidth
        val height = bounds.outHeight
        require(width in 1..12_000 && height in 1..12_000) { "Photo resolution is unsupported" }
        var sample = 1
        while (width / sample > MAX_SIDE * 2 || height / sample > MAX_SIDE * 2) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Selected photo could not be decoded")
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val image = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { decoded.recycle() }
        } else {
            decoded
        }

        val buffer = ByteArrayOutputStream()
        var selected = false
        try {
            check(image.compress(Bitmap.CompressFormat.JPEG, 82, buffer)) { "Failed to encode selected photo" }
            val bytes = buffer.toByteArray()
            check(bytes.isNotEmpty() && bytes.size <= MAX_JPEG_BYTES) { "Selected photo is too large" }
            selected = true
            return SelectedPhoto(bytes, image)
        } finally {
            if (!selected) image.recycle()
        }
    }

    private const val MAX_SIDE = 512
    private const val MAX_JPEG_BYTES = 2 * 1024 * 1024
}
