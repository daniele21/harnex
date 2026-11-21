package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.audit.InferenceAuditAdmission
import io.github.daniele21.localllm.audit.InferenceAuditExecutionIdentity
import io.github.daniele21.localllm.audit.InferenceAuditFailureCode
import io.github.daniele21.localllm.audit.InferenceAuditInput
import io.github.daniele21.localllm.audit.InferenceAuditMessage
import io.github.daniele21.localllm.audit.InferenceAuditMetrics
import io.github.daniele21.localllm.audit.InferenceAuditOrigin
import io.github.daniele21.localllm.audit.InferenceAuditPrepared
import io.github.daniele21.localllm.audit.InferenceAuditRepository
import io.github.daniele21.localllm.audit.InferenceAuditResult
import io.github.daniele21.localllm.audit.InferenceAuditStatus
import io.github.daniele21.localllm.audit.InferenceAuditTerminal
import io.github.daniele21.localllm.audit.InferenceAuditTerminalCode
import io.github.daniele21.localllm.audit.InferenceAuditTerminalContent
import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.PrepareResult
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.RuntimeSnapshot
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.observability.NoOpTelemetryRepository
import io.github.daniele21.localllm.observability.TelemetryRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Resolves trusted audit attribution at the composition boundary before generation is admitted. */
fun interface InferenceAuditOriginResolver {
    fun resolve(request: GenerationRequest): InferenceAuditOrigin
}

/** Consumes the runtime-owned rendered prompt exactly when the Prepared event reaches the audit gate. */
fun interface InferenceAuditEffectivePromptResolver {
    fun consume(requestId: RequestId): String?

    fun expect(requestId: RequestId) = Unit

    fun discard(requestId: RequestId) {
        consume(requestId)
    }
}

/** Runtime-private sink. Prompt content must never be forwarded to public events, logs, or telemetry. */
fun interface InferenceAuditEffectivePromptSink {
    fun publish(requestId: RequestId, prompt: String)
}

/**
 * One-shot bridge between runtime prompt planning and the strict Activity audit gate.
 *
 * Audit admission arms a request before it enters the runtime. The runtime may publish only for an
 * armed request, and Prepared consumes/removes the value exactly once. Non-audited requests are
 * ignored so this bridge cannot become an accidental secondary prompt store.
 */
class OneShotInferenceAuditEffectivePromptBridge :
    InferenceAuditEffectivePromptResolver,
    InferenceAuditEffectivePromptSink {
    private val expected = ConcurrentHashMap.newKeySet<RequestId>()
    private val prompts = ConcurrentHashMap<RequestId, String>()

    override fun expect(requestId: RequestId) {
        check(!prompts.containsKey(requestId) && expected.add(requestId)) {
            "Effective prompt bridge request is already armed"
        }
    }

    override fun publish(requestId: RequestId, prompt: String) {
        if (!expected.remove(requestId)) return
        check(prompts.putIfAbsent(requestId, prompt) == null) {
            "Effective prompt bridge already contains a prompt for this request"
        }
    }

    override fun consume(requestId: RequestId): String? {
        if (expected.remove(requestId)) {
            error("Runtime did not publish the effective prompt before Prepared")
        }
        return prompts.remove(requestId)
    }

    override fun discard(requestId: RequestId) {
        expected.remove(requestId)
        prompts.remove(requestId)
    }

    fun clear() {
        expected.clear()
        prompts.clear()
    }
}

enum class InferenceAuditWritePhase {
    ADMISSION,
    PREPARED,
    RUNNING,
    TERMINAL,
}

/** Safe, typed failure used when strict local audit persistence cannot uphold its contract. */
class InferenceAuditClientException(val failureCode: InferenceAuditFailureCode, val phase: InferenceAuditWritePhase) :
    IllegalStateException("Inference audit ${phase.name.lowercase()} persistence failed (${failureCode.name})")

/**
 * LocalLlmClient decorator that makes the ADR-0017 audit ledger a correctness gate.
 *
 * The delegate remains the canonical runtime owner. This adapter owns only audit admission and
 * coarse lifecycle persistence. It never writes prompt/output content to telemetry or logs.
 */
@Suppress("TooManyFunctions")
class InferenceAuditLocalLlmClient(
    private val delegate: LocalLlmClient,
    private val auditRepository: InferenceAuditRepository,
    private val originResolver: InferenceAuditOriginResolver,
    private val telemetryRepository: TelemetryRepository = NoOpTelemetryRepository,
    private val effectivePromptResolver: InferenceAuditEffectivePromptResolver = InferenceAuditEffectivePromptResolver { null },
    private val epochClock: EpochClock = EpochClock { System.currentTimeMillis() },
) : LocalLlmClient {
    override fun runtimeSnapshot(): RuntimeSnapshot = delegate.runtimeSnapshot()

    override fun prepare(applicationId: ApplicationId, useCaseId: UseCaseId): PrepareResult = delegate.prepare(applicationId, useCaseId)

    override fun createSession(applicationId: ApplicationId, useCaseId: UseCaseId): SessionId =
        delegate.createSession(applicationId, useCaseId)

    override fun createSession(applicationId: ApplicationId, useCaseId: UseCaseId, options: SessionOptions): SessionId =
        delegate.createSession(applicationId, useCaseId, options)

    override fun generate(request: GenerationRequest, listener: GenerationListener): GenerationHandle {
        admit(request)
        effectivePromptResolver.expect(request.requestId)
        val auditListener = AuditGenerationListener(
            request = request,
            delegate = listener,
            auditRepository = auditRepository,
            telemetryRepository = telemetryRepository,
            effectivePromptResolver = effectivePromptResolver,
            epochClock = epochClock,
        )
        val handle = try {
            delegate.generate(request, auditListener)
        } catch (error: Throwable) {
            effectivePromptResolver.discard(request.requestId)
            if (!auditListener.isTerminal()) {
                terminalizeSynchronousFailure(request.requestId)
            }
            throw error
        }
        auditListener.attachHandle(handle)
        return handle
    }

    override fun closeSession(sessionId: SessionId) {
        delegate.closeSession(sessionId)
    }

    private fun admit(request: GenerationRequest) {
        val origin = originResolver.resolve(request)
        require(origin.applicationId == request.applicationId) { "Audit origin application does not match generation request" }
        require(origin.useCaseId == request.useCaseId) { "Audit origin use case does not match generation request" }
        when (val existing = auditRepository.find(request.requestId)) {
            is InferenceAuditResult.Failure -> throw InferenceAuditClientException(existing.code, InferenceAuditWritePhase.ADMISSION)

            is InferenceAuditResult.Success -> if (existing.value != null) {
                throw InferenceAuditClientException(InferenceAuditFailureCode.INVALID_STATE, InferenceAuditWritePhase.ADMISSION)
            }
        }
        val admission = InferenceAuditAdmission(
            requestId = request.requestId,
            origin = origin,
            receivedAtEpochMs = epochClock.nowEpochMs(),
            input = request.input.toAuditInput(),
        )
        requireAuditSuccess(auditRepository.admit(admission), InferenceAuditWritePhase.ADMISSION)
    }

    private fun terminalizeSynchronousFailure(requestId: RequestId) {
        val terminal = InferenceAuditTerminal(
            requestId = requestId,
            status = InferenceAuditStatus.FAILED,
            completedAtEpochMs = epochClock.nowEpochMs(),
            terminalCode = InferenceAuditTerminalCode("GENERATION_START_FAILED"),
        )
        requireAuditSuccess(auditRepository.recordTerminal(terminal), InferenceAuditWritePhase.TERMINAL)
    }
}

private class AuditGenerationListener(
    private val request: GenerationRequest,
    private val delegate: GenerationListener,
    private val auditRepository: InferenceAuditRepository,
    private val telemetryRepository: TelemetryRepository,
    private val effectivePromptResolver: InferenceAuditEffectivePromptResolver,
    private val epochClock: EpochClock,
) : GenerationListener {
    private val handle = AtomicReference<GenerationHandle?>()
    private val handleReady = CountDownLatch(1)
    private val auditTerminal = AtomicBoolean(false)
    private val cancelOnAttach = AtomicBoolean(false)

    fun attachHandle(value: GenerationHandle) {
        handle.set(value)
        handleReady.countDown()
        if (cancelOnAttach.get()) {
            runCatching(value::cancel)
        }
    }

    fun isTerminal(): Boolean = auditTerminal.get()

    override fun onEvent(event: GenerationEvent) {
        if (auditTerminal.get()) return
        when (event) {
            is GenerationEvent.Queued -> delegate.onEvent(event)
            is GenerationEvent.Prepared -> onPrepared(event)
            is GenerationEvent.Started -> onStarted(event)
            is GenerationEvent.TextDelta -> delegate.onEvent(event)
            is GenerationEvent.Completed -> onCompleted(event)
            is GenerationEvent.Failed -> onFailed(event)
        }
    }

    private fun onPrepared(event: GenerationEvent.Prepared) {
        val effectivePrompt = runCatching { effectivePromptResolver.consume(event.requestId) }.getOrElse {
            failBeforeDecode(InferenceAuditFailureCode.INVALID_STATE, InferenceAuditWritePhase.PREPARED)
            return
        }
        val technicalRun = runCatching { telemetryRepository.findRun(event.requestId) }.getOrNull()
        val prepared = InferenceAuditPrepared(
            requestId = event.requestId,
            preparedAtEpochMs = epochClock.nowEpochMs(),
            effectivePrompt = effectivePrompt,
            execution = InferenceAuditExecutionIdentity(
                modelDigest = event.modelDigest,
                modelLoadKind = technicalRun?.modelLoadKind ?: io.github.daniele21.localllm.contracts.ModelLoadKind.UNKNOWN,
                presetId = event.configuration.preset?.id?.value,
                presetVersion = event.configuration.preset?.version,
                backendId = technicalRun?.backendId,
                backendRevision = technicalRun?.backendRevision,
                backendExecutionFingerprint = technicalRun?.backendExecutionFingerprint,
                effectivePlacement = technicalRun?.effectivePlacement?.name,
                useCaseRevision = technicalRun?.useCaseRevision,
                bindingRevision = technicalRun?.bindingRevision,
            ),
        )
        when (val result = auditRepository.markPrepared(prepared)) {
            is InferenceAuditResult.Success -> delegate.onEvent(event)
            is InferenceAuditResult.Failure -> failBeforeDecode(result.code, InferenceAuditWritePhase.PREPARED)
        }
    }

    private fun onStarted(event: GenerationEvent.Started) {
        when (val result = auditRepository.markRunning(event.requestId, epochClock.nowEpochMs())) {
            is InferenceAuditResult.Success -> delegate.onEvent(event)
            is InferenceAuditResult.Failure -> failBeforeDecode(result.code, InferenceAuditWritePhase.RUNNING)
        }
    }

    private fun onCompleted(event: GenerationEvent.Completed) {
        effectivePromptResolver.discard(event.requestId)
        val terminal = InferenceAuditTerminal(
            requestId = event.requestId,
            status = InferenceAuditStatus.COMPLETED,
            completedAtEpochMs = epochClock.nowEpochMs(),
            content = InferenceAuditTerminalContent(
                answerOutput = event.answerOutput,
                reasoningOutput = event.reasoningOutput,
            ),
            metrics = event.metrics.toAuditMetrics(),
        )
        when (val result = auditRepository.recordTerminal(terminal)) {
            is InferenceAuditResult.Success -> {
                auditTerminal.set(true)
                delegate.onEvent(event)
            }

            is InferenceAuditResult.Failure -> failTerminal(result.code)
        }
    }

    private fun onFailed(event: GenerationEvent.Failed) {
        effectivePromptResolver.discard(event.requestId)
        val status = if (event.error is LocalLlmError.Cancelled) {
            InferenceAuditStatus.CANCELLED
        } else {
            InferenceAuditStatus.FAILED
        }
        val terminal = InferenceAuditTerminal(
            requestId = event.requestId,
            status = status,
            completedAtEpochMs = epochClock.nowEpochMs(),
            terminalCode = InferenceAuditTerminalCode(event.error.code.toAuditTerminalCode()),
        )
        when (val result = auditRepository.recordTerminal(terminal)) {
            is InferenceAuditResult.Success -> {
                auditTerminal.set(true)
                delegate.onEvent(event)
            }

            is InferenceAuditResult.Failure -> failTerminal(result.code)
        }
    }

    private fun failBeforeDecode(code: InferenceAuditFailureCode, phase: InferenceAuditWritePhase) {
        effectivePromptResolver.discard(request.requestId)
        if (!auditTerminal.compareAndSet(false, true)) return
        cancelOnAttach.set(true)
        if (handleReady.await(HANDLE_ATTACH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            handle.get()?.let { generationHandle -> runCatching(generationHandle::cancel) }
        }
        delegate.onEvent(GenerationEvent.Failed(request.requestId, auditFailure(code, phase)))
    }

    private fun failTerminal(code: InferenceAuditFailureCode) {
        effectivePromptResolver.discard(request.requestId)
        if (!auditTerminal.compareAndSet(false, true)) return
        delegate.onEvent(
            GenerationEvent.Failed(
                request.requestId,
                auditFailure(code, InferenceAuditWritePhase.TERMINAL),
            ),
        )
    }

    private companion object {
        const val HANDLE_ATTACH_TIMEOUT_MS = 5_000L
    }
}

private fun GenerationInput.toAuditInput(): InferenceAuditInput = when (this) {
    is GenerationInput.Text -> InferenceAuditInput.Text(value)

    is GenerationInput.ImageQuestion -> InferenceAuditInput.Text(question)

    is GenerationInput.RawCompletion -> InferenceAuditInput.RawCompletion(value)

    is GenerationInput.Messages -> InferenceAuditInput.Messages(
        values.map { message -> InferenceAuditMessage(message.role, message.content) },
    )
}

private fun GenerationMetrics.toAuditMetrics(): InferenceAuditMetrics = InferenceAuditMetrics(
    queueMs = queueMs,
    modelLoadMs = modelLoadMs,
    timeToFirstTokenMs = timeToFirstTokenMs,
    totalMs = totalMs,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    decodeTokensPerSecond = decodeTokensPerSecond,
    prefillMs = prefillMs,
    decodeMs = decodeMs,
    modelLoadKind = modelLoadKind,
    stopReason = stopReason,
    promptPlanningMs = promptPlanningMs,
    contextCreationMs = contextCreationMs,
    timeToFirstAnswerMs = timeToFirstAnswerMs,
    reasoningTokens = reasoningTokens,
    answerTokens = answerTokens,
)

private fun String.toAuditTerminalCode(): String {
    val normalized = uppercase().map { character ->
        if (character in 'A'..'Z' || character in '0'..'9' || character == '_') character else '_'
    }.joinToString("")
    return normalized.take(64).ifBlank { "RUNTIME_FAILURE" }
}

private fun auditFailure(code: InferenceAuditFailureCode, phase: InferenceAuditWritePhase): LocalLlmError =
    LocalLlmError.NativeRuntime("Inference audit ${phase.name.lowercase()} persistence failed (${code.name})")

private fun requireAuditSuccess(result: InferenceAuditResult<Unit>, phase: InferenceAuditWritePhase) {
    if (result is InferenceAuditResult.Failure) {
        throw InferenceAuditClientException(result.code, phase)
    }
}
