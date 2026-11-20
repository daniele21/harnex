package io.github.daniele21.localllm.externalembedded

/** One bounded admission policy for text-only and photo questions, before runtime state changes. */
internal object ChatInputAdmission {
    data class Rejection(val message: String, val vision: Boolean)

    fun validate(input: String, imageBytes: ByteArray?, visionReady: Boolean): Rejection? {
        val imageError = when {
            imageBytes == null -> null
            !visionReady -> "Tap Prepare vision first"
            imageBytes.isEmpty() || imageBytes.size > MAX_IMAGE_BYTES -> "Selected image is invalid or too large"
            else -> null
        }
        if (imageError != null) return Rejection(imageError, true)
        val textError = when {
            input.isBlank() -> "Write a message before sending"
            input.length > EphemeralChatHistory.MAX_USER_CHARS ->
                "Message limit: ${EphemeralChatHistory.MAX_USER_CHARS} characters"
            else -> null
        }
        return textError?.let { Rejection(it, false) }
    }

    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
}
