package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.StopReason
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.VisionStage
import io.github.daniele21.localllm.models.ChatTemplatePolicy
import io.github.daniele21.localllm.models.GgufModelProfile
import java.io.File

data class BackendModelSource(val digest: ModelDigest, val file: File, val sizeBytes: Long) {
    init {
        require(sizeBytes >= 0) { "Model source size must not be negative" }
    }
}

interface BackendModelHandle {
    val digest: ModelDigest
    val profileId: String
    val loadDurationMs: Long
}

interface BackendContextHandle {
    val model: BackendModelHandle
    val contextSize: Int
}

enum class BackendExecutionFactAvailability {
    KNOWN,
    UNAVAILABLE,
}

data class BackendExecutionEvidence(
    val backendId: String,
    val backendRevision: String?,
    val materialFingerprint: String,
    val effectivePlacement: BackendExecutionFactAvailability,
) {
    init {
        require(backendId.isNotBlank()) { "Backend execution evidence ID must not be blank" }
        require(backendRevision == null || backendRevision.isNotBlank()) {
            "Backend execution evidence revision must not be blank"
        }
        require(SHA256_PATTERN.matches(materialFingerprint)) {
            "Backend execution material fingerprint must be SHA-256"
        }
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

data class BackendModelCapabilities(
    val maximumContextTokens: Int,
    val supportsGrammar: Boolean,
    val supportsReasoningTransition: Boolean = false,
)

data class BackendPromptPlanningRequest(
    val input: GenerationInput,
    val systemPrompt: String?,
    val chatTemplatePolicy: ChatTemplatePolicy,
    val thinkingMode: ThinkingMode = ThinkingMode.DISABLED,
)

data class BackendPromptPlan(
    val prompt: String,
    val tokenCount: Int,
    val chatTemplateId: String,
    val chatTemplateSource: ChatTemplateSource,
    val stopTokenIds: Set<Int> = emptySet(),
    val stopSequences: List<String> = emptyList(),
    val imageBytes: ByteArray? = null,
)

data class BackendContextConfiguration(val contextSize: Int) {
    init {
        require(contextSize > 0) { "Context size must be positive" }
    }
}

data class BackendReasoningControl(val maxReasoningTokens: Int, val closeMarker: String, val forcedCloseText: String) {
    init {
        require(maxReasoningTokens > 0) { "Reasoning token budget must be positive" }
        require(closeMarker.isNotBlank()) { "Reasoning close marker must not be blank" }
        require(forcedCloseText.isNotBlank()) { "Forced reasoning close text must not be blank" }
        require(closeMarker in forcedCloseText) { "Forced reasoning close text must contain the close marker" }
    }
}

data class BackendGenerationRequest(
    val requestId: String,
    val prompt: String,
    val maxOutputTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float = 0f,
    val presencePenalty: Float = 0f,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val seed: Long,
    val outputConstraint: OutputConstraint = OutputConstraint.Text,
    val stopTokenIds: Set<Int> = emptySet(),
    val stopSequences: List<String> = emptyList(),
    val reasoningControl: BackendReasoningControl? = null,
    val imageBytes: ByteArray? = null,
)

data class BackendGenerationMetrics(
    val inputTokens: Int,
    val outputTokens: Int,
    val promptDurationMs: Long,
    val generationDurationMs: Long,
    val stopReason: StopReason = StopReason.UNKNOWN,
    val reasoningTokens: Int? = null,
    val answerTokens: Int? = null,
)

sealed interface BackendGenerationOutcome {
    data class Completed(val metrics: BackendGenerationMetrics) : BackendGenerationOutcome
    data class Cancelled(val metrics: BackendGenerationMetrics) : BackendGenerationOutcome
}

class BackendException(val code: String, message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

@Suppress("TooManyFunctions")
interface InferenceBackend {
    val id: String
    val revision: String?
        get() = null

    fun initialize()
    fun shutdown()
    fun loadModel(source: BackendModelSource, profile: GgufModelProfile): BackendModelHandle
    fun unloadModel(model: BackendModelHandle)
    fun modelCapabilities(model: BackendModelHandle): BackendModelCapabilities
    fun planPrompt(model: BackendModelHandle, request: BackendPromptPlanningRequest): BackendPromptPlan

    fun createContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendContextConfiguration,
    ): BackendContextHandle

    fun executionEvidence(context: BackendContextHandle): BackendExecutionEvidence? = null

    fun releaseContext(context: BackendContextHandle)

    fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome

    fun cancel(requestId: String): Boolean
}

/**
 * Optional extension of the SAME backend/runtime path for content-free native
 * image stage progress. Non-vision backends retain the original generate contract.
 */
interface VisionProgressInferenceBackend {
    fun generateWithVisionProgress(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
        onVisionProgress: (VisionStage, Long) -> Boolean,
    ): BackendGenerationOutcome
}
