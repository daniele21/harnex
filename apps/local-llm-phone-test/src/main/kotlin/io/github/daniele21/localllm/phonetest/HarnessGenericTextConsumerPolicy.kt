package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConsumerLimits
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.ConsumerReasoningCapability
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.runtime.ConsumerUseCasePolicy

/** Host policy for bounded stateless text generation exposed to authorized Consumer apps. */
internal object HarnessGenericTextConsumerPolicy {
    const val REVISION = "generic-text-v1"
    const val MAX_INPUT_CHARACTERS = 12_000
    const val MAX_JSON_SCHEMA_CHARACTERS = 4_096

    fun create(
        applicationId: ApplicationId,
        preset: InferencePresetRef = HarnessSharedRuntimeBindings.genericTextDefaultPreset,
    ): ConsumerUseCasePolicy = ConsumerUseCasePolicy(
        applicationId = applicationId,
        useCaseId = HarnessSharedRuntimeBindings.genericTextUseCaseId,
        revision = REVISION,
        exposedPresets = setOf(preset),
        defaultPreset = preset,
        reasoning = ConsumerReasoningCapability.NOT_SUPPORTED,
        outputConstraints = setOf(ConsumerOutputConstraintKind.TEXT),
        defaultOutputConstraint = ConsumerOutputConstraintKind.TEXT,
        sessionKinds = setOf(SessionKind.STATELESS),
        defaultSessionKind = SessionKind.STATELESS,
        limits = ConsumerLimits(
            maxInputCharacters = MAX_INPUT_CHARACTERS,
            maxConversationMessages = 1,
            maxJsonSchemaCharacters = MAX_JSON_SCHEMA_CHARACTERS,
        ),
    )
}
