package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.ContextPolicy
import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionOptions
import java.util.UUID
import java.util.concurrent.Executors

private fun PlaygroundRequestOptions.toGenerationOverrides(): GenerationOverrides {
    val preset = presetId?.let { InferencePresetRef(InferencePresetId(it), PHONE_INFERENCE_PRESET_VERSION) }
    val custom = preset == null
    return GenerationOverrides(
        maxOutputTokens = maxOutputTokens.takeIf { custom },
        temperature = temperature.takeIf { custom },
        topP = topP.takeIf { custom },
        topK = topK.takeIf { custom },
        minP = minP.takeIf { custom },
        presencePenalty = presencePenalty.takeIf { custom },
        thinkingMode = thinkingMode.takeIf { custom },
        repeatPenalty = repeatPenalty.takeIf { custom },
        repeatLastN = repeatLastN.takeIf { custom },
        seedPolicy = seedPolicy.takeIf { custom },
        preset = preset,
    )
}

@Suppress("TooManyFunctions", "ReturnCount", "CyclomaticComplexMethod", "NestedBlockDepth")
internal class PhonePlaygroundController(private val runtimeGraph: HarnessRuntimeGraph, private val listener: (PlaygroundState) -> Unit) :
    PlaygroundEffects {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var state = PlaygroundState()
    private var harness: PhoneHarness? = null
    private var activeSession: SessionId? = null
    private var activeRequestId: RequestId? = null
    private var activeHandle: GenerationHandle? = null

    val active: Boolean
        get() = synchronized(lock) { state.active || activeSession != null }

    override fun snapshot(): PlaygroundState = synchronized(lock) { state }

    override fun start(model: ImportedPhoneModel, prompt: String, options: PlaygroundRequestOptions): Boolean {
        val normalizedPrompt = prompt.trim()
        require(normalizedPrompt.isNotBlank()) { "Prompt must not be blank" }
        require(normalizedPrompt.length <= MAX_PROMPT_CHARACTERS) {
            "Prompt exceeds $MAX_PROMPT_CHARACTERS characters"
        }
        val requestId = synchronized(lock) {
            if (state.active || activeSession != null) return false
            RequestId(UUID.randomUUID().toString()).also {
                activeRequestId = it
                state = PlaygroundState(
                    phase = PlaygroundPhase.PREPARING,
                    detail = "Verifying model and preparing runtime",
                )
            }
        }
        publish(snapshot())
        executor.execute {
            startOnWorker(
                model = model,
                requestId = requestId,
                prompt = normalizedPrompt,
                options = options,
            )
        }
        return true
    }

    override fun cancel(): Boolean {
        val handle = synchronized(lock) { activeHandle } ?: return false
        return runCatching {
            handle.cancel()
            val current = synchronized(lock) {
                if (state.active) {
                    state = state.copy(
                        cancellationAvailable = false,
                        cancellationRequested = true,
                        detail = "Cancellation requested",
                    )
                }
                state
            }
            publish(current)
            true
        }.getOrDefault(false)
    }

    override fun releaseRuntime(onComplete: () -> Unit): Boolean {
        val resources = synchronized(lock) {
            if (state.active || activeSession != null) return false
            val result = RuntimeResources(harness?.client, activeSession, activeHandle)
            harness = null
            activeSession = null
            activeRequestId = null
            activeHandle = null
            state = PlaygroundState(
                phase = PlaygroundPhase.PREPARING,
                detail = "Releasing playground runtime",
            )
            result
        }
        publish(snapshot())
        executor.execute {
            closeSessionResources(resources)
            runtimeGraph.unloadIdleModel()
            val current = synchronized(lock) {
                state = PlaygroundState()
                state
            }
            publish(current)
            onComplete()
        }
        return true
    }

    override fun close() {
        val resources = synchronized(lock) {
            val result = RuntimeResources(harness?.client, activeSession, activeHandle)
            harness = null
            activeSession = null
            activeRequestId = null
            activeHandle = null
            state = PlaygroundState()
            result
        }
        closeSessionResources(resources)
        executor.shutdownNow()
    }

    private fun startOnWorker(model: ImportedPhoneModel, requestId: RequestId, prompt: String, options: PlaygroundRequestOptions) {
        try {
            val verification = runtimeGraph.modelStore.verify(model.digest)
            check(verification.valid) { "Model integrity verification failed: ${verification.detail}" }
            val currentHarness = runtimeGraph.harnessFor(model, HarnessRuntimePurpose.PLAYGROUND)
            synchronized(lock) { harness = currentHarness }
            val prepared = currentHarness.client.prepare(
                currentHarness.applicationId,
                currentHarness.useCaseId,
            )
            check(prepared.ready) { "Model preparation failed: ${prepared.detail}" }
            val session = currentHarness.client.createSession(
                currentHarness.applicationId,
                currentHarness.useCaseId,
                SessionOptions(
                    contextPolicy = options.contextTokens?.let(ContextPolicy::Manual) ?: ContextPolicy.Auto,
                ),
            )
            registerSession(requestId, session)
            val generationRequest = GenerationRequest(
                requestId = requestId,
                sessionId = session,
                applicationId = currentHarness.applicationId,
                useCaseId = currentHarness.useCaseId,
                input = prompt,
                overrides = options.toGenerationOverrides(),
            )
            val handle = currentHarness.client.generate(
                generationRequest,
                GenerationListener(::onGenerationEvent),
            )
            registerHandle(requestId, handle)
        } catch (error: Throwable) {
            failStart(requestId, error)
        }
    }

    private fun registerSession(requestId: RequestId, session: SessionId) {
        val current = synchronized(lock) {
            check(activeRequestId == requestId) { "Playground request changed while preparing" }
            activeSession = session
            state = state.copy(detail = "Inference session created")
            state
        }
        publish(current)
    }

    private fun registerHandle(requestId: RequestId, handle: GenerationHandle) {
        val current = synchronized(lock) {
            if (activeRequestId == requestId && state.active) {
                activeHandle = handle
                state = state.copy(cancellationAvailable = true)
            }
            state
        }
        publish(current)
    }

    private fun onGenerationEvent(event: GenerationEvent) {
        val terminal = event is GenerationEvent.Completed || event is GenerationEvent.Failed
        val current = synchronized(lock) {
            if (event.requestId != activeRequestId) return
            state = when (event) {
                is GenerationEvent.Queued -> state.copy(
                    phase = PlaygroundPhase.QUEUED,
                    detail = "Generation queued",
                )

                is GenerationEvent.Started -> state.copy(
                    phase = PlaygroundPhase.GENERATING,
                    detail = "Generating locally",
                )

                is GenerationEvent.Prepared -> state.copy(
                    detail = "${event.configuration.preset?.id?.value ?: "Custom"} · " +
                        "${event.configuration.promptTokenCount}/${event.configuration.contextSize} context tokens",
                    effectiveConfiguration = event.configuration,
                )

                is GenerationEvent.TextDelta -> appendOutput(event.text, event.generatedTokens, event.contentType)

                is GenerationEvent.VisionProgress -> state.copy(
                    detail = "Vision: ${event.stage.name.lowercase().replace('_', ' ')} · ${event.elapsedMs} ms",
                )

                is GenerationEvent.Completed -> completedState(event)

                is GenerationEvent.Failed -> failedState(event.error)
            }
            if (terminal) activeHandle = null
            state
        }
        publish(current)
        if (terminal) {
            runCatching { executor.execute(::cleanupAfterTerminal) }
        }
    }

    private fun appendOutput(text: String, generatedTokens: Int, contentType: GenerationContentType): PlaygroundState {
        val combined = appendBounded(state.output, text)
        val reasoning = if (contentType == GenerationContentType.REASONING) {
            appendBounded(state.reasoningOutput, text)
        } else {
            state.reasoningOutput
        }
        val answer = if (contentType == GenerationContentType.ANSWER) {
            appendBounded(state.answerOutput, text)
        } else {
            state.answerOutput
        }
        val channelTruncated = when (contentType) {
            GenerationContentType.REASONING -> reasoning.length < state.reasoningOutput.length + text.length
            GenerationContentType.ANSWER -> answer.length < state.answerOutput.length + text.length
        }
        return state.copy(
            phase = PlaygroundPhase.GENERATING,
            output = combined,
            reasoningOutput = reasoning,
            answerOutput = answer,
            outputTruncated = state.outputTruncated || combined.length < state.output.length + text.length || channelTruncated,
            generatedTokens = generatedTokens,
            detail = if (contentType == GenerationContentType.REASONING && state.answerOutput.isEmpty()) {
                "Thinking locally"
            } else {
                "Generating answer"
            },
        )
    }

    private fun appendBounded(current: String, text: String): String {
        val remaining = (MAX_OUTPUT_CHARACTERS - current.length).coerceAtLeast(0)
        return current + text.take(remaining)
    }

    private fun completedState(event: GenerationEvent.Completed): PlaygroundState = state.copy(
        phase = PlaygroundPhase.COMPLETED,
        output = event.output.take(MAX_OUTPUT_CHARACTERS),
        reasoningOutput = event.reasoningOutput.take(MAX_OUTPUT_CHARACTERS),
        answerOutput = event.answerOutput.take(MAX_OUTPUT_CHARACTERS),
        outputTruncated = event.output.length > MAX_OUTPUT_CHARACTERS ||
            event.reasoningOutput.length > MAX_OUTPUT_CHARACTERS ||
            event.answerOutput.length > MAX_OUTPUT_CHARACTERS,
        generatedTokens = event.metrics.outputTokens,
        cancellationAvailable = false,
        metrics = PlaygroundMetrics.from(event.metrics),
        errorCode = null,
        detail = if (event.answerOutput.isEmpty() && event.reasoningOutput.isNotEmpty()) {
            "Generation ended before a final answer was produced"
        } else {
            "Generation completed"
        },
    )

    private fun failedState(error: LocalLlmError): PlaygroundState = state.copy(
        phase = if (error is LocalLlmError.Cancelled) PlaygroundPhase.CANCELLED else PlaygroundPhase.FAILED,
        cancellationAvailable = false,
        errorCode = error.code,
        detail = if (error is LocalLlmError.Cancelled) {
            "Generation cancelled"
        } else {
            sanitizeFailure("${error.code}: ${error.message}")
        },
    )

    private fun cleanupAfterTerminal() {
        val session = synchronized(lock) { activeSession }
        val client = synchronized(lock) { harness?.client }
        val cleanupFailed = session != null && runCatching { client?.closeSession(session) }.isFailure
        val current = synchronized(lock) {
            if (cleanupFailed) {
                state = state.copy(
                    phase = PlaygroundPhase.FAILED,
                    errorCode = "SESSION_CLEANUP_FAILED",
                    detail = "Inference session cleanup failed",
                )
            } else {
                activeSession = null
                activeRequestId = null
            }
            activeHandle = null
            state
        }
        publish(current)
    }

    private fun failStart(requestId: RequestId, error: Throwable) {
        val session = synchronized(lock) {
            if (activeRequestId != requestId) return
            activeSession
        }
        val client = synchronized(lock) { harness?.client }
        val cleanupFailed = session != null && runCatching { client?.closeSession(session) }.isFailure
        val failureDetail = sanitizeFailure(error.message ?: error.javaClass.simpleName)
        val current = synchronized(lock) {
            if (activeRequestId != requestId) return
            activeSession = if (cleanupFailed) session else null
            activeRequestId = null
            activeHandle = null
            state = state.copy(
                phase = PlaygroundPhase.FAILED,
                cancellationAvailable = false,
                errorCode = if (cleanupFailed) "SESSION_CLEANUP_FAILED" else "INFERENCE_START_FAILED",
                detail = if (cleanupFailed) {
                    "Inference session cleanup failed; original failure: $failureDetail"
                } else {
                    failureDetail
                },
            )
            state
        }
        publish(current)
    }

    private fun sanitizeFailure(detail: String): String = detail
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(MAX_FAILURE_DETAIL_CHARACTERS)

    private fun closeSessionResources(resources: RuntimeResources) {
        runCatching { resources.handle?.cancel() }
        runCatching { resources.session?.let { resources.client?.closeSession(it) } }
    }

    private fun publish(current: PlaygroundState) {
        listener(current)
    }

    private data class RuntimeResources(val client: LocalLlmClient?, val session: SessionId?, val handle: GenerationHandle?)

    private companion object {
        const val MAX_PROMPT_CHARACTERS = 32_768
        const val MAX_OUTPUT_CHARACTERS = 131_072
        const val MAX_FAILURE_DETAIL_CHARACTERS = 1_024
    }
}
