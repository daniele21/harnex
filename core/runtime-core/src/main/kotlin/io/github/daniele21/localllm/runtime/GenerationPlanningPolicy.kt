package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ConfigurationErrorCode
import io.github.daniele21.localllm.contracts.ContextPolicy
import io.github.daniele21.localllm.contracts.EffectiveGenerationMetadata
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.SeedPolicy
import io.github.daniele21.localllm.contracts.SeedPolicyType
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.models.ContextPreference
import io.github.daniele21.localllm.models.GenerationGuardPolicy
import io.github.daniele21.localllm.models.InferencePreset
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.ReasoningStreamProtocol
import io.github.daniele21.localllm.models.ResolvedUseCase

internal const val MAX_SEED_EXCLUSIVE = 0x1_0000_0000L

internal class GenerationPlanningPolicy(private val seedSource: SeedSource) {
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ThrowsCount")
    fun resolveConfiguration(request: GenerationRequest, resolvedUseCase: ResolvedUseCase): ResolvedRequestConfiguration {
        val useCase = resolvedUseCase.useCase
        val requestedPreset = request.overrides.preset ?: useCase.defaultPreset
        val preset = requestedPreset?.let { ref ->
            useCase.presets.firstOrNull { it.ref == ref }
                ?: throw GenerationPlanningException(
                    ConfigurationErrorCode.PRESET_NOT_FOUND,
                    "Preset ${ref.id.value} version ${ref.version} is not available",
                )
        }
        val defaults = preset?.generation ?: useCase.generationDefaults
        val maxOutputTokens = request.overrides.maxOutputTokens ?: defaults.maxOutputTokens
        val temperature = request.overrides.temperature ?: defaults.temperature
        val topP = request.overrides.topP ?: defaults.topP
        val topK = request.overrides.topK ?: defaults.topK
        val minP = request.overrides.minP ?: defaults.minP
        val presencePenalty = request.overrides.presencePenalty ?: defaults.presencePenalty
        val thinkingMode = request.overrides.thinkingMode ?: defaults.thinkingMode
        val repeatPenalty = request.overrides.repeatPenalty ?: defaults.repeatPenalty
        val repeatLastN = request.overrides.repeatLastN ?: defaults.repeatLastN
        val generationValuesValid = outputAndTemperatureValid(maxOutputTokens, temperature) &&
            samplingValuesValid(topP, topK, minP) &&
            penaltyValuesValid(presencePenalty, repeatPenalty, repeatLastN)
        if (!generationValuesValid) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Generation settings are outside the supported bounds",
            )
        }

        val seedPolicy = request.overrides.requestedSeedPolicy() ?: defaults.seedPolicy
        val effectiveSeed = when (seedPolicy) {
            is SeedPolicy.Fixed -> seedPolicy.value
            SeedPolicy.Random -> seedSource.nextSeed()
        }
        if (effectiveSeed !in 0 until MAX_SEED_EXCLUSIVE) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Seed source returned a value outside the unsigned 32-bit range",
            )
        }
        if (request.input is GenerationInput.RawCompletion && thinkingMode == ThinkingMode.ENABLED) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Thinking mode requires chat-template rendering and cannot be used with raw completion",
            )
        }
        if (request.input is GenerationInput.RawCompletion && !resolvedUseCase.model.chatTemplatePolicy.allowRawCompletion) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.RAW_COMPLETION_NOT_ALLOWED,
                "Raw completion is not allowed for this model profile",
            )
        }
        val baseSystemPromptVersion = preset?.systemPromptVersion ?: useCase.systemPromptVersion
        val baseSystemPrompt = preset?.systemPrompt ?: useCase.systemPrompt
        return ResolvedRequestConfiguration(
            preset = preset,
            maxOutputTokens = maxOutputTokens,
            temperature = temperature,
            topP = topP,
            topK = topK,
            minP = minP,
            presencePenalty = presencePenalty,
            thinkingMode = thinkingMode,
            repeatPenalty = repeatPenalty,
            repeatLastN = repeatLastN,
            seedPolicy = seedPolicy,
            effectiveSeed = effectiveSeed,
            systemPromptVersion = TaskDefinitionPromptComposer.effectiveVersion(baseSystemPromptVersion, request.taskDefinitions),
            systemPrompt = TaskDefinitionPromptComposer.compose(baseSystemPrompt, request.taskDefinitions),
            contextPreference = preset?.contextPreference ?: ContextPreference(),
            guardPolicy = defaults.guardPolicy,
        )
    }

    fun validateOutputConstraint(
        outputConstraint: OutputConstraint,
        resolved: ResolvedRequestConfiguration,
        resolvedUseCase: ResolvedUseCase,
        capabilities: BackendModelCapabilities,
    ) {
        val requestedMode = when (outputConstraint) {
            OutputConstraint.Text -> OutputMode.TEXT
            OutputConstraint.Json -> OutputMode.JSON
            is OutputConstraint.JsonSchema -> OutputMode.JSON_SCHEMA
        }
        val allowedModes = resolved.preset?.allowedOutputModes ?: setOf(resolvedUseCase.useCase.outputMode)
        if (requestedMode !in allowedModes ||
            (outputConstraint !is OutputConstraint.Text && !capabilities.supportsGrammar)
        ) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.OUTPUT_CONSTRAINT_UNSUPPORTED,
                "The requested output constraint is not supported by this use case and backend",
            )
        }
    }

    fun resolveContextSize(
        resolvedUseCase: ResolvedUseCase,
        options: SessionOptions,
        promptTokenCount: Int,
        maxOutputTokens: Int,
        capabilities: BackendModelCapabilities,
        preference: ContextPreference,
    ): Int = planContextSize(
        resolvedUseCase = resolvedUseCase,
        options = options,
        promptTokenCount = promptTokenCount,
        maxOutputTokens = maxOutputTokens,
        capabilities = capabilities,
        preference = preference,
    ).requestedContextTokens

    @Suppress("ThrowsCount")
    fun planContextSize(
        resolvedUseCase: ResolvedUseCase,
        options: SessionOptions,
        promptTokenCount: Int,
        maxOutputTokens: Int,
        capabilities: BackendModelCapabilities,
        preference: ContextPreference,
    ): ContextPlanningResult {
        if (promptTokenCount <= 0 || capabilities.maximumContextTokens <= 0) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.PROMPT_TOKENIZATION_FAILED,
                "Prompt tokenization did not produce a valid token count",
            )
        }
        val runtimeCapabilities = resolvedUseCase.model.runtimeCapabilities
        val required = runCatching {
            Math.addExact(Math.addExact(promptTokenCount, maxOutputTokens), runtimeCapabilities.contextSafetyReserveTokens)
        }.getOrElse {
            throw GenerationPlanningException(
                ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED,
                "Prompt and output budget exceed the supported context capacity",
            )
        }
        val approvedTiers = runtimeCapabilities.approvedContextTiers.ifEmpty { ContextSizeSelector.supportedSizes }
        val maximum = minOf(
            capabilities.maximumContextTokens,
            preference.maximumTokens ?: Int.MAX_VALUE,
            approvedTiers.maxOrNull() ?: Int.MAX_VALUE,
        )
        if (required > maximum) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED,
                "Prompt and output require $required tokens but the maximum is $maximum",
            )
        }
        return when (val policy = options.contextPolicy) {
            is ContextPolicy.Manual -> {
                if (!ContextSizeSelector.supportsManual(policy.tokens, required, maximum, approvedTiers)) {
                    throw GenerationPlanningException(
                        ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED,
                        "Prompt and output require $required tokens but the manual context is ${policy.tokens}",
                    )
                }
                ContextPlanningResult(
                    requestedContextTokens = policy.tokens,
                    minimumRequiredTokens = required,
                    memoryEligibleContextTiers = listOf(policy.tokens),
                )
            }

            ContextPolicy.Auto -> {
                val selected = ContextSizeSelector.selectAuto(
                    required = required,
                    maximum = maximum,
                    preferredMinimum = preference.preferredTokens,
                    candidateSizes = approvedTiers,
                ) ?: throw GenerationPlanningException(
                    ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED,
                    "No supported context size can contain the requested prompt and output",
                )
                ContextPlanningResult(
                    requestedContextTokens = selected,
                    minimumRequiredTokens = required,
                    memoryEligibleContextTiers = approvedTiers
                        .asSequence()
                        .distinct()
                        .filter { it in required..selected }
                        .sorted()
                        .toList(),
                )
            }
        }
    }

    fun resolveReasoningControl(
        thinkingMode: ThinkingMode,
        guardPolicy: GenerationGuardPolicy,
        streamProtocol: ReasoningStreamProtocol,
        maxOutputTokens: Int,
        capabilities: BackendModelCapabilities,
    ): BackendReasoningControl? {
        if (thinkingMode != ThinkingMode.ENABLED ||
            !guardPolicy.enabled ||
            !capabilities.supportsReasoningTransition
        ) {
            return null
        }
        val closeMarker = streamProtocol.closeMarker ?: return null
        val forcedCloseText = streamProtocol.forcedCloseText ?: return null
        if (maxOutputTokens < MIN_CONTROLLED_THINKING_OUTPUT_TOKENS) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Thinking mode requires at least $MIN_CONTROLLED_THINKING_OUTPUT_TOKENS output tokens",
            )
        }
        val answerReserve = minOf(guardPolicy.answerReserveTokens, maxOutputTokens / 2).coerceAtLeast(1)
        val reasoningBudget = minOf(guardPolicy.thinkingTokenBudget, maxOutputTokens - answerReserve)
        if (reasoningBudget <= 0) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Thinking budget must leave capacity for a final answer",
            )
        }
        return BackendReasoningControl(
            maxReasoningTokens = reasoningBudget,
            closeMarker = closeMarker,
            forcedCloseText = forcedCloseText,
        )
    }

    private fun outputAndTemperatureValid(maxOutputTokens: Int, temperature: Float): Boolean =
        maxOutputTokens in 1..MAX_OUTPUT_TOKENS && temperature.isFinite() && temperature in 0f..2f

    private fun samplingValuesValid(topP: Float, topK: Int, minP: Float): Boolean = topP.isFinite() && topP > 0f && topP <= 1f &&
        topK in 0..MAX_TOP_K &&
        minP.isFinite() && minP in 0f..1f

    private fun penaltyValuesValid(presencePenalty: Float, repeatPenalty: Float, repeatLastN: Int): Boolean =
        presencePenalty.isFinite() && presencePenalty in 0f..2f &&
            repeatPenalty.isFinite() && repeatPenalty in MIN_REPEAT_PENALTY..MAX_REPEAT_PENALTY &&
            repeatLastN in 0..MAX_REPEAT_LAST_N &&
            (repeatPenalty == MIN_REPEAT_PENALTY || repeatLastN != 0)

    private companion object {
        const val MAX_OUTPUT_TOKENS = 32_768
        const val MAX_TOP_K = 1_000
        const val MIN_REPEAT_PENALTY = 1f
        const val MAX_REPEAT_PENALTY = 2f
        const val MAX_REPEAT_LAST_N = 4_096
        const val MIN_CONTROLLED_THINKING_OUTPUT_TOKENS = 16
    }
}

internal data class ContextPlanningResult(
    val requestedContextTokens: Int,
    val minimumRequiredTokens: Int,
    val memoryEligibleContextTiers: List<Int>,
)

internal data class ResolvedRequestConfiguration(
    val preset: InferencePreset?,
    val maxOutputTokens: Int,
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val minP: Float,
    val presencePenalty: Float,
    val thinkingMode: ThinkingMode,
    val repeatPenalty: Float,
    val repeatLastN: Int,
    val seedPolicy: SeedPolicy,
    val effectiveSeed: Long,
    val systemPromptVersion: String,
    val systemPrompt: String?,
    val contextPreference: ContextPreference,
    val guardPolicy: GenerationGuardPolicy,
) {
    /** The supplied context size is actual ordinary capacity or per-sequence batch capacity. */
    fun effectiveMetadata(promptPlan: BackendPromptPlan, contextSize: Int): EffectiveGenerationMetadata = EffectiveGenerationMetadata(
        preset = preset?.ref,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        presencePenalty = presencePenalty,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        requestedSeedPolicy = seedPolicy.toType(),
        effectiveSeed = effectiveSeed,
        maxOutputTokens = maxOutputTokens,
        contextSize = contextSize,
        promptTokenCount = promptPlan.tokenCount,
        chatTemplateId = promptPlan.chatTemplateId,
        chatTemplateSource = promptPlan.chatTemplateSource,
        systemPromptVersion = systemPromptVersion,
        thinkingMode = thinkingMode,
    )

    /** Ordinary generation may pass reasoning control; evaluation batching passes null. */
    fun backendRequest(
        request: GenerationRequest,
        promptPlan: BackendPromptPlan,
        reasoningControl: BackendReasoningControl?,
    ): BackendGenerationRequest = BackendGenerationRequest(
        requestId = request.requestId.value,
        prompt = promptPlan.prompt,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        presencePenalty = presencePenalty,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        seed = effectiveSeed,
        outputConstraint = request.outputConstraint,
        stopTokenIds = promptPlan.stopTokenIds,
        stopSequences = promptPlan.stopSequences,
        reasoningControl = reasoningControl,
    )

    private fun SeedPolicy.toType(): SeedPolicyType = when (this) {
        SeedPolicy.Random -> SeedPolicyType.RANDOM
        is SeedPolicy.Fixed -> SeedPolicyType.FIXED
    }
}

internal class GenerationPlanningException(val reason: ConfigurationErrorCode, message: String) : IllegalArgumentException(message)
