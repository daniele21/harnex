package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.ConfigurationErrorCode
import io.github.daniele21.localllm.contracts.EffectiveGenerationMetadata
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SeedPolicy
import io.github.daniele21.localllm.contracts.SeedPolicyType
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.AppModelBinding
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GenerationDefaults
import io.github.daniele21.localllm.models.GenerationGuardPolicy
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.ReasoningStreamProtocol
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.models.UseCaseCachePolicy
import io.github.daniele21.localllm.models.UseCaseProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationPlanningPolicyTest {
    @Test
    fun `random seed is resolved by injected source without runtime state`() {
        val policy = GenerationPlanningPolicy(SeedSource { 42L })

        val resolved = policy.resolveConfiguration(request(), resolvedUseCase())

        assertEquals(SeedPolicy.Random, resolved.seedPolicy)
        assertEquals(42L, resolved.effectiveSeed)
        assertEquals(16, resolved.maxOutputTokens)
    }

    @Test
    fun `request overrides remain authoritative over defaults`() {
        val policy = GenerationPlanningPolicy(SeedSource { 99L })
        val request = request().copy(
            overrides = GenerationOverrides(
                maxOutputTokens = 24,
                temperature = 0.7f,
                topP = 0.8f,
                topK = 25,
                repeatPenalty = 1.1f,
                repeatLastN = 96,
                seedPolicy = SeedPolicy.Fixed(7L),
            ),
        )

        val resolved = policy.resolveConfiguration(request, resolvedUseCase())

        assertEquals(24, resolved.maxOutputTokens)
        assertEquals(0.7f, resolved.temperature)
        assertEquals(0.8f, resolved.topP)
        assertEquals(25, resolved.topK)
        assertEquals(1.1f, resolved.repeatPenalty)
        assertEquals(96, resolved.repeatLastN)
        assertEquals(7L, resolved.effectiveSeed)
    }

    @Test
    fun `raw completion cannot enable thinking`() {
        val policy = GenerationPlanningPolicy(SeedSource { 1L })
        val request = request().copy(
            input = GenerationInput.RawCompletion("raw prompt"),
            overrides = GenerationOverrides(thinkingMode = ThinkingMode.ENABLED),
        )

        val failure = runCatching { policy.resolveConfiguration(request, resolvedUseCase()) }.exceptionOrNull()

        assertEquals(ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION, (failure as GenerationPlanningException).reason)
    }

    @Test
    fun `output constraint is checked against use case and backend capability`() {
        val policy = GenerationPlanningPolicy(SeedSource { 1L })
        val useCase = resolvedUseCase(outputMode = OutputMode.TEXT)
        val resolved = policy.resolveConfiguration(request(), useCase)

        val failure = runCatching {
            policy.validateOutputConstraint(
                outputConstraint = OutputConstraint.Json,
                resolved = resolved,
                resolvedUseCase = useCase,
                capabilities = BackendModelCapabilities(4_096, supportsGrammar = true),
            )
        }.exceptionOrNull()

        assertEquals(ConfigurationErrorCode.OUTPUT_CONSTRAINT_UNSUPPORTED, (failure as GenerationPlanningException).reason)
    }

    @Test
    fun `auto context chooses smallest approved capacity from immutable inputs`() {
        val policy = GenerationPlanningPolicy(SeedSource { 1L })
        val useCase = resolvedUseCase()
        val resolved = policy.resolveConfiguration(request(), useCase)

        val contextSize = policy.resolveContextSize(
            resolvedUseCase = useCase,
            options = SessionOptions(),
            promptTokenCount = 100,
            maxOutputTokens = resolved.maxOutputTokens,
            capabilities = BackendModelCapabilities(4_096, supportsGrammar = true),
            preference = resolved.contextPreference,
        )

        assertEquals(1_024, contextSize)
    }

    @Test
    fun `reasoning control is derived only when backend supports transition`() {
        val policy = GenerationPlanningPolicy(SeedSource { 1L })
        val guard = GenerationGuardPolicy(
            enabled = true,
            thinkingTokenBudget = 8,
            repetitionActivationTokens = 8,
            observationWindowChars = 512,
            minPatternChars = 24,
            maxPatternChars = 64,
            repetitionOccurrences = 4,
            answerReserveTokens = 8,
        )

        val unsupported = policy.resolveReasoningControl(
            thinkingMode = ThinkingMode.ENABLED,
            guardPolicy = guard,
            streamProtocol = ReasoningStreamProtocol.QWEN35_THINK_TAGS,
            maxOutputTokens = 32,
            capabilities = BackendModelCapabilities(4_096, true, supportsReasoningTransition = false),
        )
        val supported = policy.resolveReasoningControl(
            thinkingMode = ThinkingMode.ENABLED,
            guardPolicy = guard,
            streamProtocol = ReasoningStreamProtocol.QWEN35_THINK_TAGS,
            maxOutputTokens = 32,
            capabilities = BackendModelCapabilities(4_096, true, supportsReasoningTransition = true),
        )

        assertNull(unsupported)
        assertNotNull(supported)
        assertEquals(8, supported?.maxReasoningTokens)
    }

    @Test
    fun `ordinary and batch projections preserve the exact resolved configuration`() {
        val policy = GenerationPlanningPolicy(SeedSource { 99L })
        val request = request().copy(
            overrides = GenerationOverrides(
                maxOutputTokens = 24,
                temperature = 0.4f,
                topP = 0.82f,
                topK = 16,
                minP = 0.07f,
                presencePenalty = 0.3f,
                repeatPenalty = 1.1f,
                repeatLastN = 64,
                seedPolicy = SeedPolicy.Fixed(17L),
                thinkingMode = ThinkingMode.ENABLED,
            ),
            outputConstraint = OutputConstraint.Json,
        )
        val resolved = policy.resolveConfiguration(request, resolvedUseCase(outputMode = OutputMode.JSON))
        val promptPlan = BackendPromptPlan(
            prompt = "rendered chat",
            tokenCount = 7,
            chatTemplateId = "model-template",
            chatTemplateSource = ChatTemplateSource.GGUF,
            stopTokenIds = setOf(10, 11),
            stopSequences = listOf("</s>"),
        )
        val ordinaryContextSize = 2_048
        val batchSequenceContextSize = 1_024
        val ordinaryMetadata = resolved.effectiveMetadata(promptPlan, ordinaryContextSize)
        val batchMetadata = resolved.effectiveMetadata(promptPlan, batchSequenceContextSize)

        assertEquals(
            EffectiveGenerationMetadata(
                preset = null,
                temperature = 0.4f,
                topP = 0.82f,
                topK = 16,
                minP = 0.07f,
                presencePenalty = 0.3f,
                repeatPenalty = 1.1f,
                repeatLastN = 64,
                requestedSeedPolicy = SeedPolicyType.FIXED,
                effectiveSeed = 17L,
                maxOutputTokens = 24,
                contextSize = ordinaryContextSize,
                promptTokenCount = 7,
                chatTemplateId = "model-template",
                chatTemplateSource = ChatTemplateSource.GGUF,
                systemPromptVersion = "v1",
                thinkingMode = ThinkingMode.ENABLED,
            ),
            ordinaryMetadata,
        )
        assertEquals(ordinaryMetadata.copy(contextSize = batchSequenceContextSize), batchMetadata)

        val reasoningControl = BackendReasoningControl(
            maxReasoningTokens = 8,
            closeMarker = "</think>",
            forcedCloseText = "</think>",
        )
        val ordinaryRequest = resolved.backendRequest(request, promptPlan, reasoningControl)
        val batchRequest = resolved.backendRequest(request, promptPlan, null)
        assertEquals(
            BackendGenerationRequest(
                requestId = request.requestId.value,
                prompt = "rendered chat",
                maxOutputTokens = 24,
                temperature = 0.4f,
                topP = 0.82f,
                topK = 16,
                minP = 0.07f,
                presencePenalty = 0.3f,
                repeatPenalty = 1.1f,
                repeatLastN = 64,
                seed = 17L,
                outputConstraint = OutputConstraint.Json,
                stopTokenIds = setOf(10, 11),
                stopSequences = listOf("</s>"),
                reasoningControl = reasoningControl,
            ),
            ordinaryRequest,
        )
        assertEquals(ordinaryRequest.copy(reasoningControl = null), batchRequest)
        assertEquals(
            SeedPolicyType.RANDOM,
            policy.resolveConfiguration(request(), resolvedUseCase())
                .effectiveMetadata(promptPlan, batchSequenceContextSize)
                .requestedSeedPolicy,
        )
    }

    @Test
    fun `context exhaustion rejects the complete prompt without automatic truncation`() {
        val policy = GenerationPlanningPolicy(SeedSource { 1L })
        val useCase = resolvedUseCase()
        val settings = policy.resolveConfiguration(request(), useCase)
        val capabilities = BackendModelCapabilities(4_096, supportsGrammar = true)

        val failure = runCatching {
            policy.planContextSize(
                resolvedUseCase = useCase,
                options = SessionOptions(),
                promptTokenCount = 4_096,
                maxOutputTokens = settings.maxOutputTokens,
                capabilities = capabilities,
                preference = settings.contextPreference,
            )
        }.exceptionOrNull()

        assertEquals(ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED, (failure as GenerationPlanningException).reason)

        // A consumer may explicitly select fewer complete history turns and submit a new snapshot.
        // The Harnex planner itself does not drop messages or truncate the prompt.
        val smallerSnapshot = policy.planContextSize(
            resolvedUseCase = useCase,
            options = SessionOptions(),
            promptTokenCount = 64,
            maxOutputTokens = settings.maxOutputTokens,
            capabilities = capabilities,
            preference = settings.contextPreference,
        )
        assertEquals(1_024, smallerSnapshot.requestedContextTokens)
    }

    private fun request(): GenerationRequest = GenerationRequest(
        requestId = RequestId("planning-request"),
        sessionId = SessionId("planning-session"),
        applicationId = ApplicationId("planning-app"),
        useCaseId = UseCaseId("assistant"),
        input = "hello",
    )

    private fun resolvedUseCase(outputMode: OutputMode = OutputMode.TEXT): ResolvedUseCase {
        val applicationId = ApplicationId("planning-app")
        val useCaseId = UseCaseId("assistant")
        val digest = ModelDigest("a".repeat(64))
        val model = GgufModelProfile(
            id = "planning-model",
            artifact = GgufArtifact(
                digest = digest,
                fileName = "planning.gguf",
                sizeBytes = 1024,
                architecture = "qwen2",
                quantization = "Q4_K_M",
                source = ArtifactSource.Imported("planning-test"),
            ),
            contextSize = 4_096,
            batchSize = 128,
            microBatchSize = 64,
            cpuThreads = 2,
            batchThreads = 2,
            gpuLayers = 0,
        )
        val useCase = UseCaseProfile(
            id = "planning-use-case",
            modelProfileId = model.id,
            systemPromptVersion = "v1",
            generationDefaults = GenerationDefaults(
                maxOutputTokens = 16,
                temperature = 0f,
                topP = 1f,
                topK = 0,
                seedPolicy = SeedPolicy.Random,
            ),
            outputMode = outputMode,
            cachePolicy = UseCaseCachePolicy(0, false, false, false),
            healthSuiteId = "health",
        )
        return ResolvedUseCase(
            binding = AppModelBinding(applicationId, useCaseId, useCase.id),
            useCase = useCase,
            model = model,
        )
    }
}
