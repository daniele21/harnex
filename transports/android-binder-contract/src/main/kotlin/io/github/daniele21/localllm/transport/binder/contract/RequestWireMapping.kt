package io.github.daniele21.localllm.transport.binder.contract

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SeedPolicy
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.UseCaseId

fun GenerationRequest.toWire(clientToken: ClientTokenParcel): GenerationRequestParcel = GenerationRequestParcel(
    clientToken = clientToken,
    externalRequestId = requestId.value,
    externalSessionId = sessionId.value,
    useCaseId = useCaseId.value,
    input = input.toWire(),
    overrides = overrides.toWire(),
    outputConstraint = outputConstraint.toWire(),
).also(::validateGenerationRequest)

fun GenerationRequestParcel.toCore(
    applicationId: ApplicationId,
    internalSessionId: SessionId,
    internalRequestId: RequestId,
): GenerationRequest {
    validateGenerationRequest(this)
    return GenerationRequest(
        requestId = internalRequestId,
        sessionId = internalSessionId,
        applicationId = applicationId,
        useCaseId = UseCaseId(useCaseId),
        input = input.toCore(),
        overrides = overrides.toCore(),
        outputConstraint = outputConstraint.toCore(),
    )
}

private fun GenerationInput.toWire(): GenerationInputParcel = when (this) {
    is GenerationInput.Text -> GenerationInputParcel(WireTags.INPUT_TEXT, value, emptyList())

    is GenerationInput.ImageQuestion ->
        throw IllegalArgumentException("Image input is supported only by the embedded runtime; Binder does not transport media")

    is GenerationInput.RawCompletion -> GenerationInputParcel(WireTags.INPUT_RAW_COMPLETION, value, emptyList())

    is GenerationInput.Messages ->
        GenerationInputParcel(
            typeTag = WireTags.INPUT_MESSAGES,
            text = null,
            messages = values.map { ConversationMessageParcel(it.role.toWireTag(), it.content) },
        )
}

private fun GenerationInputParcel.toCore(): GenerationInput = when (typeTag) {
    WireTags.INPUT_TEXT -> GenerationInput.Text(requireNotNull(text))

    WireTags.INPUT_RAW_COMPLETION -> GenerationInput.RawCompletion(requireNotNull(text))

    WireTags.INPUT_MESSAGES ->
        GenerationInput.Messages(
            messages.map { ConversationMessage(it.roleTag.toCoreRole(), it.content) },
        )

    else -> throw invalidWireTag("generation input", typeTag)
}

private fun GenerationOverrides.toWire(): GenerationOverridesParcel {
    val requestedSeed = requestedSeedPolicy()
    return GenerationOverridesParcel(
        presetId = preset?.id?.value,
        presetVersion = preset?.version,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        seedPolicyTag =
        when (requestedSeed) {
            null -> null
            SeedPolicy.Random -> WireTags.SEED_RANDOM
            is SeedPolicy.Fixed -> WireTags.SEED_FIXED
        },
        seedValue = (requestedSeed as? SeedPolicy.Fixed)?.value,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        thinkingModeTag = thinkingMode?.toWireTag(),
        minP = minP,
        presencePenalty = presencePenalty,
    )
}

private fun GenerationOverridesParcel.toCore(): GenerationOverrides {
    val preset = presetId?.let { InferencePresetRef(InferencePresetId(it), requireNotNull(presetVersion)) }
    val seedPolicy = seedPolicyTag.toCoreSeedPolicy(seedValue)
    val thinkingMode = thinkingModeTag.toCoreThinkingModeOrNull()
    return GenerationOverrides(
        preset = preset,
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        seedPolicy = seedPolicy,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        thinkingMode = thinkingMode,
        minP = minP,
        presencePenalty = presencePenalty,
    )
}

private fun OutputConstraint.toWire(): OutputConstraintParcel = when (this) {
    OutputConstraint.Text -> OutputConstraintParcel(WireTags.CONSTRAINT_TEXT, null)
    OutputConstraint.Json -> OutputConstraintParcel(WireTags.CONSTRAINT_JSON, null)
    is OutputConstraint.JsonSchema -> OutputConstraintParcel(WireTags.CONSTRAINT_JSON_SCHEMA, schema)
}

private fun OutputConstraintParcel.toCore(): OutputConstraint = when (typeTag) {
    WireTags.CONSTRAINT_TEXT -> OutputConstraint.Text
    WireTags.CONSTRAINT_JSON -> OutputConstraint.Json
    WireTags.CONSTRAINT_JSON_SCHEMA -> OutputConstraint.JsonSchema(requireNotNull(jsonSchema))
    else -> throw invalidWireTag("output constraint", typeTag)
}

private fun String?.toCoreSeedPolicy(seedValue: Long?): SeedPolicy? = when (this) {
    null -> null
    WireTags.SEED_RANDOM -> SeedPolicy.Random
    WireTags.SEED_FIXED -> SeedPolicy.Fixed(requireNotNull(seedValue))
    else -> throw invalidWireTag("seed policy", this)
}

private fun String?.toCoreThinkingModeOrNull(): ThinkingMode? = when (this) {
    null -> null
    WireTags.THINKING_ENABLED -> ThinkingMode.ENABLED
    WireTags.THINKING_DISABLED -> ThinkingMode.DISABLED
    else -> throw invalidWireTag("thinking mode", this)
}
