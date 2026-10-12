package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.PresetConsumerMetadata
import io.github.daniele21.localllm.models.PresetCreationSource
import io.github.daniele21.localllm.models.PresetExecutionPolicy
import io.github.daniele21.localllm.models.PresetLifecycleState
import io.github.daniele21.localllm.models.UseCaseCachePolicy
import io.github.daniele21.localllm.models.UseCaseDefinition
import io.github.daniele21.localllm.models.UseCaseDefinitionState
import io.github.daniele21.localllm.models.UseCasePresetDefinition
import io.github.daniele21.localllm.models.UseCaseRequirements

private const val GENERIC_TEXT_MINIMUM_CONTEXT_TOKENS = 4_096
private const val GENERIC_TEXT_MAX_INPUT_CHARACTERS = 12_000
private const val GENERIC_TEXT_WARM_RETENTION_MS = 60_000L

/** Generic text-generation seed graph. Runtime/model selection remains host-owned. */
internal fun HarnessBuiltInControlPlaneSpec.Companion.genericText(
    applications: List<HarnessBuiltInApplicationRequirement>,
): HarnessBuiltInControlPlaneSpec = HarnessBuiltInControlPlaneSpec(
    applications = applications.sortedBy { it.applicationId.value },
    useCase = UseCaseDefinition(
        useCaseId = HarnessSharedRuntimeBindings.genericTextUseCaseId,
        displayName = "Generic text generation",
        description = "Run bounded stateless local text generation for Consumer SDK integrations and app-owned workflows",
        requirements = UseCaseRequirements(
            outputMode = OutputMode.TEXT,
            sessionKind = SessionKind.STATELESS,
            reasoningSupported = false,
            minimumContextTokens = GENERIC_TEXT_MINIMUM_CONTEXT_TOKENS,
            maxInputCharacters = GENERIC_TEXT_MAX_INPUT_CHARACTERS,
        ),
        state = UseCaseDefinitionState.ACTIVE,
        revision = 1,
    ),
    preset = UseCasePresetDefinition(
        useCaseId = HarnessSharedRuntimeBindings.genericTextUseCaseId,
        metadata = PresetConsumerMetadata(
            presetId = HarnessSharedRuntimeBindings.genericTextDefaultPreset.id.value,
            revision = HarnessSharedRuntimeBindings.genericTextDefaultPreset.version,
            displayName = "Quality",
            description = "General-purpose non-thinking local text generation",
        ),
        creationSource = PresetCreationSource.SUGGESTED,
        state = PresetLifecycleState.PUBLISHED,
        execution = PresetExecutionPolicy(
            modelProfileId = null,
            inferencePreset = HarnessSharedRuntimeBindings.genericTextDefaultPreset,
            contextTokens = GENERIC_TEXT_MINIMUM_CONTEXT_TOKENS,
            cachePolicy = UseCaseCachePolicy(
                retainModelWarmMs = GENERIC_TEXT_WARM_RETENTION_MS,
                reuseStatelessContext = false,
                enablePrefixSnapshot = false,
                enableDeterministicResultCache = false,
            ),
        ),
    ),
    isDefaultBinding = false,
)
