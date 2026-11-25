package io.github.daniele21.localllm.embedded

import io.github.daniele21.localllm.catalog.CatalogAvailability
import io.github.daniele21.localllm.catalog.CuratedModelCatalog
import io.github.daniele21.localllm.catalog.EmbeddedCatalogTargets
import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.AppModelBinding
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.InferencePreset
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.Qwen35GenerationProfiles
import io.github.daniele21.localllm.models.Qwen35ModelTier
import io.github.daniele21.localllm.models.Qwen35RuntimeTuningProfiles
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.models.UseCaseCachePolicy
import io.github.daniele21.localllm.models.UseCaseProfile

/**
 * Fixed, reviewed initial embedded model policy. A commercial app cannot request arbitrary GGUF
 * bytes, native load parameters or a hidden model fallback through this SDK.
 */
internal object EmbeddedModelPolicy {
    const val INITIAL_MODEL_ID = "qwen35-2b-q4-k-m"
    val applicationId = ApplicationId("harnex-embedded")
    val useCaseId = UseCaseId("local-text-generation")
    val structuredPreset = InferencePresetRef(InferencePresetId("embedded-structured-json"), 1)

    fun resolve(configuration: EmbeddedConfiguration, availableProcessors: Int): ResolvedUseCase {
        require(configuration.modelId == INITIAL_MODEL_ID) { "Unsupported embedded model selection" }
        val release = CuratedModelCatalog.releases.single { it.id.modelId.value == configuration.modelId }
        require(release.availability == CatalogAvailability.ACTIVE || release.availability == CatalogAvailability.CANDIDATE) {
            "Embedded model is unavailable"
        }
        require(EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION in release.allowedTargets) {
            "Model is not authorized for embedded inference"
        }

        val tuningProfile = Qwen35RuntimeTuningProfiles.candidateForTier(Qwen35ModelTier.B2)
        val tuning = tuningProfile.resolve(availableProcessors)
        val model =
            GgufModelProfile(
                id = "embedded-" + release.profileKey.value + "-tuning-v" + tuning.profileVersion,
                artifact =
                GgufArtifact(
                    digest = release.artifact.digest,
                    fileName = release.artifact.fileName,
                    sizeBytes = release.artifact.sizeBytes,
                    architecture = release.artifact.architecture,
                    quantization = release.artifact.quantization,
                    source = ArtifactSource.Download(release.artifact.downloadUri.toString()),
                ),
                contextSize = tuning.contextTokens,
                batchSize = tuning.batchSize,
                microBatchSize = tuning.microBatchSize,
                cpuThreads = tuning.cpuThreads,
                batchThreads = tuning.batchThreads,
                gpuLayers = 0,
                useMmap = tuning.useMmap,
                useMlock = tuning.useMlock,
                flashAttention = tuning.flashAttention,
                runtimeCapabilities = tuningProfile.runtimeCapabilities(),
            )
        val useCase =
            UseCaseProfile(
                id = "embedded-local-text-v1",
                modelProfileId = model.id,
                systemPromptVersion = "embedded-instructions-v1",
                systemPrompt = configuration.systemInstructions,
                generationDefaults = Qwen35GenerationProfiles.defaultForTier(Qwen35ModelTier.B2),
                outputMode = OutputMode.TEXT,
                // The ordinary chat default remains TEXT. Only explicit structured calls
                // opt into the schema preset; the common runtime still validates grammar support.
                presets =
                listOf(
                    InferencePreset(
                        ref = structuredPreset,
                        generation = Qwen35GenerationProfiles.defaultForTier(Qwen35ModelTier.B2).copy(
                            temperature = 0f,
                            presencePenalty = 0f,
                        ),
                        systemPromptVersion = "embedded-structured-v1",
                        systemPrompt = configuration.systemInstructions ?: "Follow the supplied JSON schema.",
                        allowedOutputModes = setOf(OutputMode.JSON_SCHEMA),
                    ),
                ),
                cachePolicy =
                UseCaseCachePolicy(
                    retainModelWarmMs = 0,
                    reuseStatelessContext = false,
                    enablePrefixSnapshot = false,
                    enableDeterministicResultCache = false,
                ),
                healthSuiteId = "embedded-local-text-health-v1",
            )
        return ResolvedUseCase(
            binding = AppModelBinding(applicationId, useCaseId, useCase.id),
            useCase = useCase,
            model = model,
        )
    }
}
