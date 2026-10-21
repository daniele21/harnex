package io.github.daniele21.localllm.evaluation.engine

import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileId
import io.github.daniele21.localllm.evaluation.EvaluationExecutionProfileRef
import io.github.daniele21.localllm.evaluation.EvaluationFailure
import io.github.daniele21.localllm.evaluation.EvaluationFailureCode
import io.github.daniele21.localllm.evaluation.EvaluationFailureStage
import io.github.daniele21.localllm.models.GenerationDefaults
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.Qwen35GenerationProfile
import io.github.daniele21.localllm.models.Qwen35GenerationProfileId
import io.github.daniele21.localllm.models.Qwen35GenerationProfiles
import io.github.daniele21.localllm.models.Qwen35ModelTier
import io.github.daniele21.localllm.models.Qwen35ResolvedRuntimeTuning
import io.github.daniele21.localllm.models.Qwen35RuntimeEvidenceStatus
import io.github.daniele21.localllm.models.Qwen35RuntimeTuningProfiles

fun interface EvaluationModelTierResolver {
    fun resolve(profile: GgufModelProfile): Qwen35ModelTier?
}

data class EvaluationExecutionProfileDefinition(
    val ref: EvaluationExecutionProfileRef,
    val label: String,
    val description: String,
    val generation: GenerationDefaults,
    val runtimeTuning: Qwen35ResolvedRuntimeTuning,
) {
    val evidenceStatus: Qwen35RuntimeEvidenceStatus
        get() = runtimeTuning.evidenceStatus
}

object Qwen35EvaluationExecutionProfiles {
    fun forTier(tier: Qwen35ModelTier, availableProcessors: Int): List<EvaluationExecutionProfileDefinition> {
        val runtime = Qwen35RuntimeTuningProfiles.candidateForTier(tier).resolve(availableProcessors)
        return Qwen35GenerationProfiles.forTier(tier).map { it.toEvaluationProfile(runtime) }
    }
}

interface EvaluationExecutionProfileRegistry {
    fun available(model: ResolvedEvaluationModel): List<EvaluationExecutionProfileDefinition>

    fun resolve(model: ResolvedEvaluationModel, ref: EvaluationExecutionProfileRef): EvaluationExecutionProfileDefinition? =
        available(model).singleOrNull { it.ref == ref }
}

class Qwen35EvaluationExecutionProfileRegistry(
    private val tierResolver: EvaluationModelTierResolver,
    private val availableProcessors: () -> Int,
) : EvaluationExecutionProfileRegistry {
    override fun available(model: ResolvedEvaluationModel): List<EvaluationExecutionProfileDefinition> {
        val tier = tierResolver.resolve(model.profile) ?: return emptyList()
        return Qwen35EvaluationExecutionProfiles.forTier(tier, availableProcessors())
    }
}

class RegistryEvaluationExecutionProfilePreflight(private val registry: EvaluationExecutionProfileRegistry) :
    EvaluationExecutionProfilePreflight {
    override fun validate(profile: EvaluationExecutionProfileRef, model: ResolvedEvaluationModel): EvaluationFailure? =
        if (registry.resolve(model, profile) == null) {
            EvaluationFailure(
                stage = EvaluationFailureStage.PREFLIGHT,
                code = EvaluationFailureCode.UNSUPPORTED_EXECUTION_PROFILE,
            )
        } else {
            null
        }
}

private fun Qwen35GenerationProfile.toEvaluationProfile(runtime: Qwen35ResolvedRuntimeTuning): EvaluationExecutionProfileDefinition =
    EvaluationExecutionProfileDefinition(
        ref = EvaluationExecutionProfileRef(
            id = EvaluationExecutionProfileId(id.name.lowercase()),
            version = version,
        ),
        label = id.displayLabel(),
        description = id.description(runtime.evidenceStatus),
        generation = defaults,
        runtimeTuning = runtime,
    )

private fun Qwen35GenerationProfileId.displayLabel(): String = when (this) {
    Qwen35GenerationProfileId.QWEN35_TEXT_FAST -> "Text fast"
    Qwen35GenerationProfileId.QWEN35_TEXT_QUALITY -> "Text quality"
    Qwen35GenerationProfileId.QWEN35_THINKING -> "Thinking"
    Qwen35GenerationProfileId.QWEN35_PRECISE -> "Precise"
    Qwen35GenerationProfileId.QWEN35_JSON -> "JSON"
}

private fun Qwen35GenerationProfileId.description(evidenceStatus: Qwen35RuntimeEvidenceStatus): String {
    val mode = when (this) {
        Qwen35GenerationProfileId.QWEN35_TEXT_FAST -> "Lower output budget for fast text evaluation"
        Qwen35GenerationProfileId.QWEN35_TEXT_QUALITY -> "Default text-quality generation profile"
        Qwen35GenerationProfileId.QWEN35_THINKING -> "Thinking-enabled generation profile"
        Qwen35GenerationProfileId.QWEN35_PRECISE -> "Lower-temperature thinking-enabled profile"
        Qwen35GenerationProfileId.QWEN35_JSON -> "Thinking-disabled structured JSON profile"
    }
    return "$mode · runtime evidence ${evidenceStatus.name.lowercase()}"
}
