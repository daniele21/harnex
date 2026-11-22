package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConsumerCapabilityErrorCode
import io.github.daniele21.localllm.contracts.ConsumerCapabilityResult
import io.github.daniele21.localllm.contracts.ConsumerContentType
import io.github.daniele21.localllm.contracts.ConsumerErrorCode
import io.github.daniele21.localllm.contracts.ConsumerExecutionIdentity
import io.github.daniele21.localllm.contracts.ConsumerFailure
import io.github.daniele21.localllm.contracts.ConsumerGenerationEvent
import io.github.daniele21.localllm.contracts.ConsumerGenerationHandle
import io.github.daniele21.localllm.contracts.ConsumerGenerationInput
import io.github.daniele21.localllm.contracts.ConsumerGenerationListener
import io.github.daniele21.localllm.contracts.ConsumerGenerationRequest
import io.github.daniele21.localllm.contracts.ConsumerGenerationStartResult
import io.github.daniele21.localllm.contracts.ConsumerInferenceMetrics
import io.github.daniele21.localllm.contracts.ConsumerInferenceResult
import io.github.daniele21.localllm.contracts.ConsumerLimits
import io.github.daniele21.localllm.contracts.ConsumerLocalLlmClient
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraint
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.ConsumerPrepareRequest
import io.github.daniele21.localllm.contracts.ConsumerPrepareResult
import io.github.daniele21.localllm.contracts.ConsumerPreparedId
import io.github.daniele21.localllm.contracts.ConsumerPreparedSelection
import io.github.daniele21.localllm.contracts.ConsumerReasoningPreference
import io.github.daniele21.localllm.contracts.ConsumerSelectionRequest
import io.github.daniele21.localllm.contracts.ConsumerSessionResult
import io.github.daniele21.localllm.contracts.ConsumerStopReason
import io.github.daniele21.localllm.contracts.EffectiveConsumerReasoningMode
import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.StopReason
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.UseCaseCapabilities
import io.github.daniele21.localllm.contracts.UseCaseId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ConsumerLocalLlmFacade(
    private val applicationId: ApplicationId,
    private val policyService: ConsumerCapabilityPolicyService,
    private val delegate: LocalLlmClient,
) : ConsumerLocalLlmClient {
    private val preparedSelections = ConcurrentHashMap<ConsumerPreparedId, PreparedBinding>()
    private val sessions = ConcurrentHashMap<SessionId, SessionBinding>()

    override fun capabilities(useCaseId: UseCaseId): ConsumerCapabilityResult = policyService.discover(applicationId, useCaseId)

    override fun prepare(request: ConsumerPrepareRequest): ConsumerPrepareResult {
        val initial = policyService.validateSelection(applicationId, request.useCaseId, request.selection)
        if (initial is ConsumerPolicyDecision.Rejected) return ConsumerPrepareResult.Rejected(initial.toFailure())

        val prepareResult = runCatching { delegate.prepare(applicationId, request.useCaseId) }.getOrNull()
        if (prepareResult?.ready != true) {
            return ConsumerPrepareResult.Rejected(
                ConsumerFailure(ConsumerErrorCode.PREPARE_FAILED, "Use case preparation failed"),
            )
        }

        val capabilities = when (val discovered = policyService.discover(applicationId, request.useCaseId)) {
            is ConsumerCapabilityResult.Available -> discovered.capabilities
            is ConsumerCapabilityResult.Rejected -> return ConsumerPrepareResult.Rejected(discovered.toFailure())
        }
        val refreshedRequest = request.selection.copy(capabilityRevision = capabilities.capabilityRevision)
        return when (val validated = policyService.validateSelection(applicationId, request.useCaseId, refreshedRequest)) {
            is ConsumerPolicyDecision.Rejected -> ConsumerPrepareResult.Rejected(validated.toFailure())
            is ConsumerPolicyDecision.Accepted -> preparedResult(request.useCaseId, capabilities, validated)
        }
    }

    override fun createSession(preparedId: ConsumerPreparedId): ConsumerSessionResult {
        val binding = preparedSelections[preparedId]
            ?: return ConsumerSessionResult.Rejected(
                ConsumerFailure(ConsumerErrorCode.PREPARED_SELECTION_NOT_FOUND, "Prepared selection is unavailable"),
            )
        val validationFailure = preparedBindingFailure(binding)
        if (validationFailure != null) {
            return ConsumerSessionResult.Rejected(validationFailure)
        }

        val sessionId = runCatching {
            delegate.createSession(
                applicationId,
                binding.selection.useCaseId,
                SessionOptions(kind = binding.selection.sessionKind),
            )
        }.getOrNull()
            ?: return ConsumerSessionResult.Rejected(
                ConsumerFailure(ConsumerErrorCode.RUNTIME_FAILURE, "Unable to create consumer session"),
            )
        sessions[sessionId] = SessionBinding(binding.selection, binding.limits)
        preparedSelections.remove(preparedId, binding)
        return ConsumerSessionResult.Created(sessionId)
    }

    private fun preparedBindingFailure(binding: PreparedBinding): ConsumerFailure? {
        val request = binding.selection.toSelectionRequest()
        return when (val validated = policyService.validateSelection(applicationId, binding.selection.useCaseId, request)) {
            is ConsumerPolicyDecision.Rejected -> if (validated.code == ConsumerCapabilityErrorCode.STALE_CAPABILITY) {
                ConsumerFailure(ConsumerErrorCode.PREPARED_SELECTION_STALE, "Prepared selection is stale")
            } else {
                validated.toFailure()
            }

            is ConsumerPolicyDecision.Accepted -> if (binding.selection.matches(validated)) {
                null
            } else {
                ConsumerFailure(
                    ConsumerErrorCode.PREPARED_SELECTION_STALE,
                    "Prepared selection no longer matches host policy",
                )
            }
        }
    }

    override fun generate(request: ConsumerGenerationRequest, listener: ConsumerGenerationListener): ConsumerGenerationStartResult {
        val binding = sessions[request.sessionId]
            ?: return ConsumerGenerationStartResult.Rejected(
                ConsumerFailure(ConsumerErrorCode.SESSION_NOT_FOUND, "Consumer session is unavailable"),
            )
        val mapped = ConsumerRequestMapper.map(applicationId, request, binding)
        if (mapped is RequestMapping.Rejected) return ConsumerGenerationStartResult.Rejected(mapped.failure)
        mapped as RequestMapping.Accepted

        val projector = ConsumerEventProjector(binding.selection, listener)
        val handle = runCatching { delegate.generate(mapped.request, projector) }.getOrNull()
            ?: return ConsumerGenerationStartResult.Rejected(
                ConsumerFailure(ConsumerErrorCode.RUNTIME_FAILURE, "Unable to start generation"),
            )
        return ConsumerGenerationStartResult.Accepted(ConsumerGenerationHandleAdapter(handle))
    }

    override fun closeSession(sessionId: SessionId) {
        if (sessions.remove(sessionId) != null) {
            delegate.closeSession(sessionId)
        }
    }

    private fun preparedResult(
        useCaseId: UseCaseId,
        capabilities: UseCaseCapabilities,
        decision: ConsumerPolicyDecision.Accepted,
    ): ConsumerPrepareResult.Prepared {
        val selection = ConsumerPreparedSelection(
            preparedId = ConsumerPreparedId(UUID.randomUUID().toString()),
            useCaseId = useCaseId,
            capabilityRevision = decision.capabilityRevision,
            preset = decision.preset?.ref,
            reasoningMode = decision.reasoningMode,
            outputConstraint = decision.outputConstraint,
            sessionKind = decision.sessionKind,
        )
        preparedSelections[selection.preparedId] = PreparedBinding(selection, capabilities.limits)
        return ConsumerPrepareResult.Prepared(selection)
    }

    private data class PreparedBinding(val selection: ConsumerPreparedSelection, val limits: ConsumerLimits)

    private data class SessionBinding(val selection: ConsumerPreparedSelection, val limits: ConsumerLimits)

    private sealed interface RequestMapping {
        data class Accepted(val request: GenerationRequest) : RequestMapping

        data class Rejected(val failure: ConsumerFailure) : RequestMapping
    }

    private object ConsumerRequestMapper {
        fun map(applicationId: ApplicationId, request: ConsumerGenerationRequest, binding: SessionBinding): RequestMapping {
            val input = mapInput(request.input, binding.limits)
                ?: return RequestMapping.Rejected(
                    ConsumerFailure(ConsumerErrorCode.INVALID_INPUT, "Consumer input exceeds the authorized limits"),
                )
            val output = mapOutput(request.outputConstraint, binding.selection.outputConstraint, binding.limits)
                ?: return RequestMapping.Rejected(
                    ConsumerFailure(ConsumerErrorCode.OUTPUT_NOT_ALLOWED, "Output constraint does not match the prepared selection"),
                )
            val overrides = GenerationOverrides(
                preset = binding.selection.preset,
                thinkingMode = when (binding.selection.reasoningMode) {
                    EffectiveConsumerReasoningMode.DISABLED -> ThinkingMode.DISABLED
                    EffectiveConsumerReasoningMode.SURFACED -> ThinkingMode.ENABLED
                },
            )
            return runCatching {
                GenerationRequest(
                    requestId = request.requestId,
                    sessionId = request.sessionId,
                    applicationId = applicationId,
                    useCaseId = binding.selection.useCaseId,
                    input = input,
                    overrides = overrides,
                    outputConstraint = output,
                    taskDefinitions = request.taskDefinitions,
                )
            }.fold(
                onSuccess = RequestMapping::Accepted,
                onFailure = {
                    RequestMapping.Rejected(
                        ConsumerFailure(ConsumerErrorCode.INVALID_INPUT, "Consumer request is invalid"),
                    )
                },
            )
        }

        private fun mapInput(input: ConsumerGenerationInput, limits: ConsumerLimits): GenerationInput? = when (input) {
            is ConsumerGenerationInput.Text ->
                input.value
                    .takeIf { it.length <= limits.maxInputCharacters }
                    ?.let(GenerationInput::Text)

            is ConsumerGenerationInput.Messages ->
                input.values
                    .takeIf { messages ->
                        messages.size <= limits.maxConversationMessages &&
                            messages.sumOf { it.content.length } <= limits.maxInputCharacters
                    }
                    ?.let(GenerationInput::Messages)
        }

        private fun mapOutput(
            output: ConsumerOutputConstraint,
            expected: ConsumerOutputConstraintKind,
            limits: ConsumerLimits,
        ): OutputConstraint? = when {
            expected == ConsumerOutputConstraintKind.TEXT && output is ConsumerOutputConstraint.Text -> OutputConstraint.Text

            expected == ConsumerOutputConstraintKind.JSON && output is ConsumerOutputConstraint.Json -> OutputConstraint.Json

            expected == ConsumerOutputConstraintKind.JSON_SCHEMA && output is ConsumerOutputConstraint.JsonSchema &&
                output.schema.length <= limits.maxJsonSchemaCharacters -> OutputConstraint.JsonSchema(output.schema)

            else -> null
        }
    }

    private class ConsumerEventProjector(
        private val selection: ConsumerPreparedSelection,
        private val listener: ConsumerGenerationListener,
    ) : GenerationListener {
        private var terminal = false

        override fun onEvent(event: GenerationEvent) {
            if (terminal) return
            when (event) {
                is GenerationEvent.Queued -> listener.onEvent(ConsumerGenerationEvent.Queued(event.requestId, event.position))

                is GenerationEvent.Prepared -> listener.onEvent(
                    ConsumerGenerationEvent.Prepared(event.requestId, selection.toExecutionIdentity()),
                )

                is GenerationEvent.Started -> listener.onEvent(ConsumerGenerationEvent.Started(event.requestId))

                is GenerationEvent.TextDelta -> projectDelta(event)?.let(listener::onEvent)

                // Consumer Binder remains text-only; embedded-only image stages never cross it.
                is GenerationEvent.VisionProgress -> Unit

                is GenerationEvent.Completed -> {
                    terminal = true
                    listener.onEvent(
                        ConsumerGenerationEvent.Completed(
                            requestId = event.requestId,
                            result = ConsumerInferenceResult(
                                answer = event.answerOutput,
                                surfacedReasoning = event.reasoningOutput.takeIf {
                                    selection.reasoningMode == EffectiveConsumerReasoningMode.SURFACED
                                },
                                metrics = event.metrics.toConsumerMetrics(selection.reasoningMode),
                                execution = selection.toExecutionIdentity(),
                            ),
                        ),
                    )
                }

                is GenerationEvent.Failed -> {
                    terminal = true
                    listener.onEvent(ConsumerGenerationEvent.Failed(event.requestId, event.error.toConsumerFailure()))
                }
            }
        }

        private fun projectDelta(event: GenerationEvent.TextDelta): ConsumerGenerationEvent.ContentDelta? = when (event.contentType) {
            GenerationContentType.ANSWER -> ConsumerGenerationEvent.ContentDelta(
                event.requestId,
                event.text,
                ConsumerContentType.ANSWER,
            )

            GenerationContentType.REASONING -> if (selection.reasoningMode == EffectiveConsumerReasoningMode.SURFACED) {
                ConsumerGenerationEvent.ContentDelta(event.requestId, event.text, ConsumerContentType.REASONING)
            } else {
                null
            }
        }
    }
}

private class ConsumerGenerationHandleAdapter(private val delegate: GenerationHandle) : ConsumerGenerationHandle {
    override val requestId = delegate.requestId

    override fun cancel() = delegate.cancel()
}

private fun ConsumerPreparedSelection.toExecutionIdentity() = ConsumerExecutionIdentity(
    useCaseId = useCaseId,
    capabilityRevision = capabilityRevision,
    preset = preset,
    reasoningMode = reasoningMode,
    outputConstraint = outputConstraint,
    sessionKind = sessionKind,
)

private fun GenerationMetrics.toConsumerMetrics(reasoningMode: EffectiveConsumerReasoningMode) = ConsumerInferenceMetrics(
    outputTokens = outputTokens,
    timeToFirstTokenMs = timeToFirstTokenMs,
    totalMs = totalMs,
    decodeTokensPerSecond = decodeTokensPerSecond,
    inputTokens = inputTokens,
    reasoningTokens = reasoningTokens.takeIf { reasoningMode == EffectiveConsumerReasoningMode.SURFACED },
    answerTokens = answerTokens,
    queueMs = queueMs,
    stopReason = stopReason.toConsumerStopReason(),
)

private fun StopReason.toConsumerStopReason(): ConsumerStopReason = when (this) {
    StopReason.END_OF_GENERATION -> ConsumerStopReason.END_OF_GENERATION
    StopReason.MAX_OUTPUT_TOKENS -> ConsumerStopReason.MAX_OUTPUT_TOKENS
    StopReason.STOP_SEQUENCE -> ConsumerStopReason.STOP_SEQUENCE
    StopReason.GRAMMAR_COMPLETE -> ConsumerStopReason.GRAMMAR_COMPLETE
    StopReason.GENERATION_GUARD_REPETITION -> ConsumerStopReason.GENERATION_GUARD_REPETITION
    StopReason.GENERATION_GUARD_THINKING_BUDGET -> ConsumerStopReason.GENERATION_GUARD_THINKING_BUDGET
    StopReason.UNKNOWN -> ConsumerStopReason.UNKNOWN
}

private fun ConsumerPreparedSelection.toSelectionRequest() = ConsumerSelectionRequest(
    capabilityRevision = capabilityRevision,
    preset = preset,
    reasoning = when (reasoningMode) {
        EffectiveConsumerReasoningMode.DISABLED -> ConsumerReasoningPreference.DISABLED
        EffectiveConsumerReasoningMode.SURFACED -> ConsumerReasoningPreference.SURFACED_IF_SUPPORTED
    },
    outputConstraint = outputConstraint,
    sessionKind = sessionKind,
)

private fun ConsumerPreparedSelection.matches(decision: ConsumerPolicyDecision.Accepted): Boolean =
    capabilityRevision == decision.capabilityRevision &&
        preset == decision.preset?.ref &&
        reasoningMode == decision.reasoningMode &&
        outputConstraint == decision.outputConstraint &&
        sessionKind == decision.sessionKind

private fun ConsumerPolicyDecision.Rejected.toFailure() = ConsumerFailure(code.toConsumerErrorCode(), "Consumer selection was rejected")

private fun ConsumerCapabilityResult.Rejected.toFailure() =
    ConsumerFailure(code.toConsumerErrorCode(), "Consumer capability is unavailable")

private fun ConsumerCapabilityErrorCode.toConsumerErrorCode(): ConsumerErrorCode = when (this) {
    ConsumerCapabilityErrorCode.USE_CASE_NOT_ALLOWED -> ConsumerErrorCode.USE_CASE_NOT_ALLOWED
    ConsumerCapabilityErrorCode.STALE_CAPABILITY -> ConsumerErrorCode.STALE_CAPABILITY
    ConsumerCapabilityErrorCode.MODEL_UNAVAILABLE -> ConsumerErrorCode.MODEL_UNAVAILABLE
    ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE -> ConsumerErrorCode.CAPABILITY_INCOMPATIBLE
    ConsumerCapabilityErrorCode.PRESET_NOT_ALLOWED -> ConsumerErrorCode.PRESET_NOT_ALLOWED
    ConsumerCapabilityErrorCode.REASONING_NOT_ALLOWED -> ConsumerErrorCode.REASONING_NOT_ALLOWED
    ConsumerCapabilityErrorCode.REASONING_REQUIRED -> ConsumerErrorCode.REASONING_REQUIRED
    ConsumerCapabilityErrorCode.OUTPUT_NOT_ALLOWED -> ConsumerErrorCode.OUTPUT_NOT_ALLOWED
    ConsumerCapabilityErrorCode.SESSION_KIND_NOT_ALLOWED -> ConsumerErrorCode.SESSION_KIND_NOT_ALLOWED
}

private fun LocalLlmError.toConsumerFailure(): ConsumerFailure = when (this) {
    is LocalLlmError.Cancelled -> ConsumerFailure(ConsumerErrorCode.CANCELLED, "Generation was cancelled")
    else -> ConsumerFailure(ConsumerErrorCode.RUNTIME_FAILURE, "Generation failed")
}
