package io.github.daniele21.localllm.llamacpp

import java.util.Base64
import io.github.daniele21.localllm.contracts.VisionStage

interface NativeStreamingCallback {
    fun onChunk(base64Text: String, generatedTokens: Int): Boolean

    /** Only typed milestones and monotonic times; never transmit image or prompt. */
    fun onVisionStage(stage: String, elapsedMs: Long): Boolean = true
}

interface NativeLlamaStreamingApi {
    @Suppress("LongParameterList")
    fun generateStreaming(
        contextHandle: Long,
        requestId: String,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        outputConstraintType: String,
        outputSchema: String?,
        stopTokenIds: IntArray,
        stopSequences: Array<String>,
        reasoningMaxTokens: Int,
        reasoningCloseMarker: String?,
        reasoningForcedCloseText: String?,
        callback: NativeStreamingCallback,
    ): Array<String>

    @Suppress("LongParameterList")
    fun generateImageStreaming(
        contextHandle: Long,
        requestId: String,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        outputConstraintType: String,
        outputSchema: String?,
        stopTokenIds: IntArray,
        stopSequences: Array<String>,
        reasoningMaxTokens: Int,
        reasoningCloseMarker: String?,
        reasoningForcedCloseText: String?,
        callback: NativeStreamingCallback,
        imageBytes: ByteArray,
        projectorPath: String,
    ): Array<String> = arrayOf("error", "UNSUPPORTED_MODEL", "Image generation is not implemented by this native adapter")

    fun cancel(requestId: String): Array<String>
}

class JniLlamaStreamingApi : NativeLlamaStreamingApi {
    init {
        System.loadLibrary("local_llm_jni")
    }

    @Suppress("LongParameterList")
    external override fun generateStreaming(
        contextHandle: Long,
        requestId: String,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        outputConstraintType: String,
        outputSchema: String?,
        stopTokenIds: IntArray,
        stopSequences: Array<String>,
        reasoningMaxTokens: Int,
        reasoningCloseMarker: String?,
        reasoningForcedCloseText: String?,
        callback: NativeStreamingCallback,
    ): Array<String>

    @Suppress("LongParameterList")
    external override fun generateImageStreaming(
        contextHandle: Long,
        requestId: String,
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        outputConstraintType: String,
        outputSchema: String?,
        stopTokenIds: IntArray,
        stopSequences: Array<String>,
        reasoningMaxTokens: Int,
        reasoningCloseMarker: String?,
        reasoningForcedCloseText: String?,
        callback: NativeStreamingCallback,
        imageBytes: ByteArray,
        projectorPath: String,
    ): Array<String>

    external override fun cancel(requestId: String): Array<String>
}

class LlamaCppStreamingBridge(private val nativeApi: NativeLlamaStreamingApi = JniLlamaStreamingApi()) {
    fun generate(
        context: LoadedNativeContext,
        requestId: String,
        prompt: String,
        config: NativeGenerationConfig,
        listener: NativeStreamingListener,
        imageBytes: ByteArray? = null,
        projectorPath: String? = null,
        onVisionProgress: (VisionStage, Long) -> Boolean = { _, _ -> true },
    ): NativeStreamingResult {
        val validationError = validate(requestId, prompt, config)
        if (validationError != null) {
            return NativeStreamingResult.Failure(validationError)
        }

        val callback = object : NativeStreamingCallback {
            override fun onVisionStage(stage: String, elapsedMs: Long): Boolean {
                val parsed = VisionStage.entries.firstOrNull { it.name == stage } ?: return false
                if (elapsedMs < 0) return false
                return onVisionProgress(parsed, elapsedMs)
            }

            override fun onChunk(base64Text: String, generatedTokens: Int): Boolean = try {
                val text = String(Base64.getDecoder().decode(base64Text), Charsets.UTF_8)
                listener.onChunk(NativeTextChunk(text = text, generatedTokens = generatedTokens))
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        return decodeTerminal(
            if (imageBytes != null) {
                if (projectorPath.isNullOrBlank()) {
                    return NativeStreamingResult.Failure(
                        StreamingNativeError(StreamingNativeErrorCode.UNSUPPORTED_MODEL, "Verified vision projector is not available"),
                    )
                }
                nativeApi.generateImageStreaming(
                    contextHandle = context.handle.value,
                    requestId = requestId,
                    prompt = prompt,
                    maxOutputTokens = config.maxOutputTokens,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    minP = config.minP,
                    presencePenalty = config.presencePenalty,
                    repeatPenalty = config.repeatPenalty,
                    repeatLastN = config.repeatLastN,
                    seed = config.seed,
                    outputConstraintType = config.outputConstraintType,
                    outputSchema = config.outputSchema,
                    stopTokenIds = config.stopTokenIds,
                    stopSequences = config.stopSequences.toTypedArray(),
                    reasoningMaxTokens = config.reasoningMaxTokens ?: 0,
                    reasoningCloseMarker = config.reasoningCloseMarker,
                    reasoningForcedCloseText = config.reasoningForcedCloseText,
                    callback = callback,
                    imageBytes = imageBytes,
                    projectorPath = projectorPath,
                )
            } else {
                nativeApi.generateStreaming(
                    contextHandle = context.handle.value,
                    requestId = requestId,
                    prompt = prompt,
                    maxOutputTokens = config.maxOutputTokens,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.topK,
                    minP = config.minP,
                    presencePenalty = config.presencePenalty,
                    repeatPenalty = config.repeatPenalty,
                    repeatLastN = config.repeatLastN,
                    seed = config.seed,
                    outputConstraintType = config.outputConstraintType,
                    outputSchema = config.outputSchema,
                    stopTokenIds = config.stopTokenIds,
                    stopSequences = config.stopSequences.toTypedArray(),
                    reasoningMaxTokens = config.reasoningMaxTokens ?: 0,
                    reasoningCloseMarker = config.reasoningCloseMarker,
                    reasoningForcedCloseText = config.reasoningForcedCloseText,
                    callback = callback,
                )
            },
        )
    }

    fun cancel(requestId: String): StreamingCancelResult {
        if (requestId.isBlank()) {
            return StreamingCancelResult.Failure(
                StreamingNativeError(
                    code = StreamingNativeErrorCode.INVALID_ARGUMENT,
                    message = "Request ID must not be blank",
                ),
            )
        }
        val response = nativeApi.cancel(requestId)
        if (response.size == CANCEL_FIELD_COUNT && response[0] == OK) {
            val wasRunning = response[1].toBooleanStrictOrNull()
                ?: return StreamingCancelResult.Failure(
                    protocolError("Native cancellation state is invalid: ${response[1]}"),
                )
            return StreamingCancelResult.Accepted(wasRunning = wasRunning)
        }
        return StreamingCancelResult.Failure(decodeError(response))
    }

    private fun validate(requestId: String, prompt: String, config: NativeGenerationConfig): StreamingNativeError? {
        if (requestId.isBlank()) {
            return StreamingNativeError(
                code = StreamingNativeErrorCode.INVALID_ARGUMENT,
                message = "Request ID must not be blank",
            )
        }
        val generationError = config.validationError(prompt) ?: return null
        return StreamingNativeError(
            code = StreamingNativeErrorCode.INVALID_ARGUMENT,
            message = generationError.message,
        )
    }

    private fun decodeTerminal(response: Array<String>): NativeStreamingResult {
        if (response.size == TERMINAL_FIELD_COUNT && (response[0] == OK || response[0] == CANCELLED)) {
            return try {
                val reasoningTokens = response[6].toInt().takeIf { it >= 0 }
                val answerTokens = response[7].toInt().takeIf { it >= 0 }
                val metrics = NativeStreamingMetrics(
                    inputTokens = response[1].toInt(),
                    outputTokens = response[2].toInt(),
                    promptDurationMs = response[3].toLong(),
                    generationDurationMs = response[4].toLong(),
                    stopReason = response[5],
                    reasoningTokens = reasoningTokens,
                    answerTokens = answerTokens,
                )
                if (response[0] == OK) {
                    NativeStreamingResult.Completed(metrics)
                } else {
                    NativeStreamingResult.Cancelled(metrics)
                }
            } catch (error: NumberFormatException) {
                NativeStreamingResult.Failure(
                    protocolError("Native streaming metrics are invalid: ${error.message}"),
                )
            }
        }
        return NativeStreamingResult.Failure(decodeError(response))
    }

    private fun decodeError(response: Array<String>): StreamingNativeError {
        if (response.size != ERROR_FIELD_COUNT || response[0] != ERROR) {
            return protocolError("Malformed native response: ${response.joinToString(separator = "|")}")
        }
        val code = StreamingNativeErrorCode.entries.firstOrNull { it.name == response[1] }
            ?: StreamingNativeErrorCode.NATIVE_PROTOCOL
        return StreamingNativeError(code = code, message = response[2])
    }

    private fun protocolError(message: String): StreamingNativeError = StreamingNativeError(
        code = StreamingNativeErrorCode.NATIVE_PROTOCOL,
        message = message,
    )

    private companion object {
        const val OK = "ok"
        const val CANCELLED = "cancelled"
        const val ERROR = "error"
        const val TERMINAL_FIELD_COUNT = 8
        const val CANCEL_FIELD_COUNT = 2
        const val ERROR_FIELD_COUNT = 3
    }
}

fun interface NativeStreamingListener {
    fun onChunk(chunk: NativeTextChunk): Boolean
}

data class NativeTextChunk(val text: String, val generatedTokens: Int)

data class NativeStreamingMetrics(
    val inputTokens: Int,
    val outputTokens: Int,
    val promptDurationMs: Long,
    val generationDurationMs: Long,
    val stopReason: String,
    val reasoningTokens: Int? = null,
    val answerTokens: Int? = null,
)

sealed interface NativeStreamingResult {
    data class Completed(val metrics: NativeStreamingMetrics) : NativeStreamingResult
    data class Cancelled(val metrics: NativeStreamingMetrics) : NativeStreamingResult
    data class Failure(val error: StreamingNativeError) : NativeStreamingResult
}

sealed interface StreamingCancelResult {
    data class Accepted(val wasRunning: Boolean) : StreamingCancelResult
    data class Failure(val error: StreamingNativeError) : StreamingCancelResult
}

data class StreamingNativeError(val code: StreamingNativeErrorCode, val message: String)

enum class StreamingNativeErrorCode {
    INVALID_ARGUMENT,
    UNKNOWN_HANDLE,
    DUPLICATE_REQUEST,
    CALLBACK_FAILED,
    UNSUPPORTED_MODEL,
    TOKENIZATION_FAILED,
    CONTEXT_OVERFLOW,
    DECODE_FAILED,
    SAMPLER_FAILED,
    INVALID_OUTPUT_CONSTRAINT,
    TOKEN_DECODE_FAILED,
    INTERNAL,
    NATIVE_PROTOCOL,
}
