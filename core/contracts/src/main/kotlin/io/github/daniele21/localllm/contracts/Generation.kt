package io.github.daniele21.localllm.contracts

data class GenerationRequest(
    val requestId: RequestId,
    val sessionId: SessionId,
    val applicationId: ApplicationId,
    val useCaseId: UseCaseId,
    val input: GenerationInput,
    val overrides: GenerationOverrides = GenerationOverrides(),
    val outputConstraint: OutputConstraint = OutputConstraint.Text,
    val taskDefinitions: List<TaskDefinition> = emptyList(),
) {
    init {
        TaskDefinitionLimits.validate(taskDefinitions)
    }

    constructor(
        requestId: RequestId,
        sessionId: SessionId,
        applicationId: ApplicationId,
        useCaseId: UseCaseId,
        input: String,
        overrides: GenerationOverrides = GenerationOverrides(),
        outputConstraint: OutputConstraint = OutputConstraint.Text,
        taskDefinitions: List<TaskDefinition> = emptyList(),
    ) : this(
        requestId = requestId,
        sessionId = sessionId,
        applicationId = applicationId,
        useCaseId = useCaseId,
        input = GenerationInput.Text(input),
        overrides = overrides,
        outputConstraint = outputConstraint,
        taskDefinitions = taskDefinitions,
    )

    override fun toString(): String =
        "GenerationRequest(requestId=$requestId, sessionId=$sessionId, applicationId=$applicationId, useCaseId=$useCaseId, " +
            "input=<redacted>, overrides=$overrides, outputConstraint=${outputConstraint::class.simpleName}, " +
            "taskDefinitionCount=${taskDefinitions.size})"
}

data class GenerationOverrides(
    val preset: InferencePresetRef? = null,
    val maxOutputTokens: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val seedPolicy: SeedPolicy? = null,
    val seed: Long? = null,
    val repeatPenalty: Float? = null,
    val repeatLastN: Int? = null,
    val thinkingMode: ThinkingMode? = null,
    val minP: Float? = null,
    val presencePenalty: Float? = null,
) {
    init {
        require(seedPolicy == null || seed == null) { "Specify either seedPolicy or legacy seed, not both" }
        seed?.let { SeedPolicy.Fixed(it) }
    }

    fun requestedSeedPolicy(): SeedPolicy? = seedPolicy ?: seed?.let(SeedPolicy::Fixed)
}

enum class GenerationContentType {
    REASONING,
    ANSWER,
}

/**
 * Content-free native image preparation milestones. Times are monotonic elapsed
 * milliseconds since native vision setup began, not estimates of completion.
 * Started means backend execution started, NOT that a text token was emitted.
 */
enum class VisionStage {
    PROJECTOR_LOADING,
    PROJECTOR_READY,
    IMAGE_DECODING,
    IMAGE_DECODED,
    IMAGE_TOKENIZING,
    IMAGE_TOKENIZED,
    PREFILL_STARTED,
    PREFILL_COMPLETE,
    FIRST_TOKEN,
    VISION_ENCODING_STARTED,
    VISION_EMBEDDING_PREFILL_STARTED,
    VISION_TEXT_PREFILL_STARTED,
}

sealed interface GenerationEvent {
    val requestId: RequestId

    data class Queued(override val requestId: RequestId, val position: Int) : GenerationEvent

    data class Started(override val requestId: RequestId, val modelDigest: ModelDigest) : GenerationEvent

    data class Prepared(override val requestId: RequestId, val modelDigest: ModelDigest, val configuration: EffectiveGenerationMetadata) :
        GenerationEvent

    data class VisionProgress(override val requestId: RequestId, val stage: VisionStage, val elapsedMs: Long) : GenerationEvent {
        init {
            require(elapsedMs >= 0) { "Native vision stage time must be nonnegative" }
        }
    }

    data class TextDelta(
        override val requestId: RequestId,
        val text: String,
        val generatedTokens: Int,
        val contentType: GenerationContentType = GenerationContentType.ANSWER,
    ) : GenerationEvent

    data class Completed(
        override val requestId: RequestId,
        val output: String,
        val metrics: GenerationMetrics,
        val reasoningOutput: String = "",
        val answerOutput: String = output,
    ) : GenerationEvent

    data class Failed(override val requestId: RequestId, val error: LocalLlmError) : GenerationEvent
}

data class GenerationMetrics(
    val queueMs: Long,
    val modelLoadMs: Long?,
    val timeToFirstTokenMs: Long?,
    val totalMs: Long,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val decodeTokensPerSecond: Double?,
    val prefillMs: Long? = null,
    val decodeMs: Long? = null,
    val modelLoadKind: ModelLoadKind = ModelLoadKind.UNKNOWN,
    val stopReason: StopReason = StopReason.UNKNOWN,
    val promptPlanningMs: Long? = null,
    val contextCreationMs: Long? = null,
    val timeToFirstAnswerMs: Long? = null,
    val reasoningTokens: Int? = null,
    val answerTokens: Int? = null,
)

enum class ModelLoadKind {
    COLD,
    WARM,
    UNKNOWN,
}

sealed interface LocalLlmError {
    val code: String
    val message: String

    data class Configuration(override val message: String, val reason: ConfigurationErrorCode = ConfigurationErrorCode.CONFIGURATION) :
        LocalLlmError {
        override val code: String = reason.name
    }

    data class ModelUnavailable(override val message: String) : LocalLlmError {
        override val code: String = "MODEL_UNAVAILABLE"
    }

    data class NativeRuntime(override val message: String) : LocalLlmError {
        override val code: String = "NATIVE_RUNTIME"
    }

    data class Cancelled(override val message: String = "Generation cancelled") : LocalLlmError {
        override val code: String = "CANCELLED"
    }
}

enum class ConfigurationErrorCode {
    CONFIGURATION,
    PRESET_NOT_FOUND,
    PRESET_NOT_ALLOWED,
    INVALID_GENERATION_CONFIGURATION,
    RAW_COMPLETION_NOT_ALLOWED,
    CHAT_TEMPLATE_UNAVAILABLE,
    CHAT_TEMPLATE_UNSUPPORTED,
    PROMPT_TOKENIZATION_FAILED,
    CONTEXT_CAPACITY_EXCEEDED,
    CONTEXT_RECONFIGURATION_REQUIRED,
    MEMORY_BUDGET_EXCEEDED,
    OUTPUT_CONSTRAINT_UNSUPPORTED,
    INVALID_OUTPUT_CONSTRAINT,
}

fun interface GenerationListener {
    fun onEvent(event: GenerationEvent)
}

interface GenerationHandle {
    val requestId: RequestId

    fun cancel()
}
