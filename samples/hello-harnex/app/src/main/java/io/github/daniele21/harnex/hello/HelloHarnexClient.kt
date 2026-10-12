package io.github.daniele21.harnex.hello

import android.content.Context
import io.github.daniele21.localllm.contracts.ConsumerActivationId
import io.github.daniele21.localllm.contracts.ConsumerActivationRequest
import io.github.daniele21.localllm.contracts.ConsumerActivationResult
import io.github.daniele21.localllm.contracts.ConsumerAssignedUseCasesResult
import io.github.daniele21.localllm.contracts.ConsumerContentType
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.contracts.ConsumerErrorCode
import io.github.daniele21.localllm.contracts.ConsumerGenerationEvent
import io.github.daniele21.localllm.contracts.ConsumerGenerationHandle
import io.github.daniele21.localllm.contracts.ConsumerGenerationInput
import io.github.daniele21.localllm.contracts.ConsumerGenerationListener
import io.github.daniele21.localllm.contracts.ConsumerGenerationRequest
import io.github.daniele21.localllm.contracts.ConsumerGenerationStartResult
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraint
import io.github.daniele21.localllm.contracts.ConsumerPrepareRequest
import io.github.daniele21.localllm.contracts.ConsumerPrepareResult
import io.github.daniele21.localllm.contracts.ConsumerPublishedPresetsResult
import io.github.daniele21.localllm.contracts.ConsumerSessionResult
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionObserver
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionSnapshot
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production-shaped application boundary for one Harnex use case.
 *
 * The UI owns product state. This client owns Consumer SDK lifecycle only: connection, assignment,
 * activation, prepare/session/generation and deterministic cleanup.
 */
internal class HelloHarnexClient internal constructor(
    private val runtime: HelloHarnexRuntime,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) : AutoCloseable {
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile
    private var activeHandle: ConsumerGenerationHandle? = null

    @Volatile
    private var activeSession: SessionId? = null

    @Volatile
    private var activeActivation: ConsumerActivationId? = null

    fun connect() {
        synchronized(lifecycleLock) {
            HelloHarnexClientSupport.ensureOpen(closed, "Hello Harnex client is closed")
            runtime.connect()
        }
    }

    fun disconnect() {
        synchronized(lifecycleLock) {
            HelloHarnexClientSupport.ensureOpen(closed, "Hello Harnex client is closed")
            check(!running.get()) { "Cancel or finish the active inference before disconnecting" }
            runtime.disconnect()
        }
    }

    fun run(text: String, onStatus: (String) -> Unit, onAnswerDelta: (String) -> Unit, onResult: (Result<HelloInferenceResult>) -> Unit) {
        require(text.isNotBlank()) { "Input must not be blank" }
        if (!running.compareAndSet(false, true)) {
            onResult(Result.failure(IllegalStateException("An inference request is already running")))
            return
        }
        if (
            !HelloHarnexClientSupport.submitIfOpen(lifecycleLock, closed, executor) {
                executeRun(text, onStatus, onAnswerDelta, onResult)
            }
        ) {
            running.set(false)
            onResult(Result.failure(IllegalStateException("Hello Harnex client is closed")))
        }
    }

    fun cancel() {
        activeHandle?.cancel()
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            activeHandle?.let { handle ->
                activeHandle = null
                handle.cancel()
            }
            executor.execute {
                cleanupOnExecutor()
                running.set(false)
                runtime.close()
            }
            executor.shutdown()
        }
    }

    private fun executeRun(
        text: String,
        onStatus: (String) -> Unit,
        onAnswerDelta: (String) -> Unit,
        onResult: (Result<HelloInferenceResult>) -> Unit,
    ) {
        if (closed.get()) {
            running.set(false)
            return
        }
        runCatching {
            val sessionId = prepareSession(onStatus)
            HelloHarnexClientSupport.ensureOpen(closed, "Hello Harnex execution was closed")
            startGeneration(sessionId, text, onStatus, onAnswerDelta, onResult)
        }.onFailure { failure -> finishOnExecutor(Result.failure(failure), onResult) }
    }

    private fun prepareSession(onStatus: (String) -> Unit): SessionId {
        onStatus("Discovering the Harnex assignment…")
        val assignment = HelloHarnexClientSupport.assignment(runtime.assignedUseCases(), USE_CASE_ID)
        val preset = HelloHarnexClientSupport.defaultPreset(runtime.publishedPresets(USE_CASE_ID))

        onStatus("Activating the host-owned local execution…")
        val activation = HelloHarnexClientSupport.activation(
            runtime.activate(
                ConsumerActivationRequest(
                    useCaseId = USE_CASE_ID,
                    useCaseRevision = assignment.useCaseRevision,
                    bindingRevision = assignment.bindingRevision,
                    preset = preset.preset,
                ),
            ),
        )
        activeActivation = activation.activationId
        HelloHarnexClientSupport.ensureOpen(closed, "Hello Harnex execution was closed")

        onStatus("Preparing the exact execution capability…")
        val prepared = HelloHarnexClientSupport.prepared(runtime.prepare(ConsumerPrepareRequest(USE_CASE_ID)))
        HelloHarnexClientSupport.ensureOpen(closed, "Hello Harnex execution was closed")
        return HelloHarnexClientSupport.session(runtime.createSession(prepared.preparedId)).also {
            activeSession = it
        }
    }

    private fun startGeneration(
        sessionId: SessionId,
        text: String,
        onStatus: (String) -> Unit,
        onAnswerDelta: (String) -> Unit,
        onResult: (Result<HelloInferenceResult>) -> Unit,
    ) {
        val terminal = AtomicBoolean(false)
        val requestId = RequestId("hello-${UUID.randomUUID()}")
        val request = ConsumerGenerationRequest(
            requestId = requestId,
            sessionId = sessionId,
            input = ConsumerGenerationInput.Text(text),
            outputConstraint = ConsumerOutputConstraint.Text,
        )
        val start = runtime.generate(
            request,
            ConsumerGenerationListener { event ->
                handleGenerationEvent(
                    event = event,
                    requestId = requestId,
                    terminal = terminal,
                    onStatus = onStatus,
                    onAnswerDelta = onAnswerDelta,
                    onTerminal = { result ->
                        HelloHarnexClientSupport.submitIfOpen(lifecycleLock, closed, executor) {
                            finishOnExecutor(result, onResult)
                        }
                    },
                )
            },
        )
        when (start) {
            is ConsumerGenerationStartResult.Accepted ->
                synchronized(lifecycleLock) {
                    if (closed.get()) {
                        start.handle.cancel()
                    } else {
                        activeHandle = start.handle
                    }
                }

            is ConsumerGenerationStartResult.Rejected -> {
                terminal.set(true)
                finishOnExecutor(
                    Result.failure(
                        consumerFailure(
                            "generation start",
                            start.failure.code,
                            start.failure.message,
                        ),
                    ),
                    onResult,
                )
            }
        }
    }

    private fun finishOnExecutor(result: Result<HelloInferenceResult>, onResult: (Result<HelloInferenceResult>) -> Unit) {
        cleanupOnExecutor()
        running.set(false)
        if (!closed.get()) onResult(result)
    }

    private fun cleanupOnExecutor() {
        activeSession?.let { runCatching { runtime.closeSession(it) } }
        activeSession = null
        activeHandle = null
        activeActivation?.let { runCatching { runtime.deactivate(it) } }
        activeActivation = null
    }

    companion object {
        fun create(context: Context, onConnectionChanged: (SharedRuntimeConnectionSnapshot) -> Unit): HelloHarnexClient = HelloHarnexClient(
            runtime = BinderHelloHarnexRuntime.create(
                context = context,
                onConnectionChanged = SharedRuntimeConnectionObserver(onConnectionChanged),
            ),
        )
    }
}

private object HelloHarnexClientSupport {
    fun ensureOpen(closed: AtomicBoolean, message: String) {
        check(!closed.get()) { message }
    }

    fun submitIfOpen(lock: Any, closed: AtomicBoolean, executor: ExecutorService, block: () -> Unit): Boolean = synchronized(lock) {
        if (closed.get()) {
            false
        } else {
            executor.execute(block)
            true
        }
    }

    fun assignment(result: ConsumerAssignedUseCasesResult, useCaseId: UseCaseId) = when (result) {
        is ConsumerAssignedUseCasesResult.Available ->
            result.assignments.singleOrNull { it.useCaseId == useCaseId }
                ?: throw HelloHarnexException(
                    kind = HelloHarnexFailureKind.CONFIGURATION_REQUIRED,
                    stage = "assignment",
                    detail = "The Generic text generation use case is not assigned to this app",
                )

        is ConsumerAssignedUseCasesResult.Rejected ->
            throw controlPlaneFailure("assignment", result.failure.code, result.failure.message)
    }

    fun defaultPreset(result: ConsumerPublishedPresetsResult) = when (result) {
        is ConsumerPublishedPresetsResult.Available ->
            result.presets.singleOrNull { it.isDefault }
                ?: throw HelloHarnexException(
                    kind = HelloHarnexFailureKind.CONFIGURATION_REQUIRED,
                    stage = "preset discovery",
                    detail = "The assigned use case has no default published preset",
                )

        is ConsumerPublishedPresetsResult.Rejected ->
            throw controlPlaneFailure("preset discovery", result.failure.code, result.failure.message)
    }

    fun activation(result: ConsumerActivationResult) = when (result) {
        is ConsumerActivationResult.Activated -> result.activation

        is ConsumerActivationResult.Rejected ->
            throw controlPlaneFailure("activation", result.failure.code, result.failure.message)
    }

    fun prepared(result: ConsumerPrepareResult) = when (result) {
        is ConsumerPrepareResult.Prepared -> result.selection

        is ConsumerPrepareResult.Rejected ->
            throw consumerFailure("prepare", result.failure.code, result.failure.message)
    }

    fun session(result: ConsumerSessionResult) = when (result) {
        is ConsumerSessionResult.Created -> result.sessionId

        is ConsumerSessionResult.Rejected ->
            throw consumerFailure("session creation", result.failure.code, result.failure.message)
    }
}

internal data class HelloInferenceResult(val answer: String, val metrics: String)

internal enum class HelloHarnexFailureKind {
    AUTHORIZATION_REQUIRED,
    CONFIGURATION_REQUIRED,
    MODEL_NOT_READY,
    CONNECTION,
    CANCELLED,
    RUNTIME,
}

internal class HelloHarnexException(val kind: HelloHarnexFailureKind, val stage: String, val detail: String) :
    IllegalStateException("$stage: $detail") {
    val userMessage: String
        get() = when (kind) {
            HelloHarnexFailureKind.AUTHORIZATION_REQUIRED ->
                "Authorization required. Open Harnex and authorize this app."

            HelloHarnexFailureKind.CONFIGURATION_REQUIRED ->
                "Harnex is reachable, but this app's use case is not fully configured."

            HelloHarnexFailureKind.MODEL_NOT_READY ->
                "The assigned local model is not ready in Harnex."

            HelloHarnexFailureKind.CONNECTION ->
                "The Harnex connection is unavailable."

            HelloHarnexFailureKind.CANCELLED -> "Inference cancelled."

            HelloHarnexFailureKind.RUNTIME -> detail
        }
}

private fun controlPlaneFailure(stage: String, code: ConsumerControlPlaneErrorCode, message: String): HelloHarnexException =
    HelloHarnexException(
        kind = when (code) {
            ConsumerControlPlaneErrorCode.UNKNOWN_APPLICATION,
            ConsumerControlPlaneErrorCode.APPLICATION_NOT_AUTHORIZED,
            -> HelloHarnexFailureKind.AUTHORIZATION_REQUIRED

            ConsumerControlPlaneErrorCode.USE_CASE_NOT_ASSIGNED,
            ConsumerControlPlaneErrorCode.PRESET_NOT_EXPOSED,
            ConsumerControlPlaneErrorCode.STALE_REVISION,
            ConsumerControlPlaneErrorCode.CONFIGURATION_REQUIRED,
            -> HelloHarnexFailureKind.CONFIGURATION_REQUIRED

            ConsumerControlPlaneErrorCode.MODEL_UNAVAILABLE,
            ConsumerControlPlaneErrorCode.MODEL_CONFLICT,
            -> HelloHarnexFailureKind.MODEL_NOT_READY

            ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE,
            ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
            -> HelloHarnexFailureKind.CONNECTION

            ConsumerControlPlaneErrorCode.ACTIVATION_ALREADY_ACTIVE,
            ConsumerControlPlaneErrorCode.INVALID_REQUEST,
            ConsumerControlPlaneErrorCode.RUNTIME_FAILURE,
            -> HelloHarnexFailureKind.RUNTIME
        },
        stage = stage,
        detail = "$code: $message",
    )

private fun consumerFailure(stage: String, code: ConsumerErrorCode, message: String): HelloHarnexException = HelloHarnexException(
    kind = when (code) {
        ConsumerErrorCode.USE_CASE_NOT_ALLOWED -> HelloHarnexFailureKind.AUTHORIZATION_REQUIRED

        ConsumerErrorCode.MODEL_UNAVAILABLE -> HelloHarnexFailureKind.MODEL_NOT_READY

        ConsumerErrorCode.CANCELLED -> HelloHarnexFailureKind.CANCELLED

        ConsumerErrorCode.CAPABILITY_INCOMPATIBLE,
        ConsumerErrorCode.PRESET_NOT_ALLOWED,
        ConsumerErrorCode.REASONING_NOT_ALLOWED,
        ConsumerErrorCode.REASONING_REQUIRED,
        ConsumerErrorCode.OUTPUT_NOT_ALLOWED,
        ConsumerErrorCode.SESSION_KIND_NOT_ALLOWED,
        ConsumerErrorCode.STALE_CAPABILITY,
        ConsumerErrorCode.PREPARED_SELECTION_STALE,
        ConsumerErrorCode.PREPARED_SELECTION_NOT_FOUND,
        -> HelloHarnexFailureKind.CONFIGURATION_REQUIRED

        ConsumerErrorCode.INVALID_INPUT,
        ConsumerErrorCode.PREPARE_FAILED,
        ConsumerErrorCode.SESSION_NOT_FOUND,
        ConsumerErrorCode.RUNTIME_FAILURE,
        -> HelloHarnexFailureKind.RUNTIME
    },
    stage = stage,
    detail = "$code: $message",
)

private fun handleGenerationEvent(
    event: ConsumerGenerationEvent,
    requestId: RequestId,
    terminal: AtomicBoolean,
    onStatus: (String) -> Unit,
    onAnswerDelta: (String) -> Unit,
    onTerminal: (Result<HelloInferenceResult>) -> Unit,
) {
    if (event.requestId != requestId || terminal.get()) return
    when (event) {
        is ConsumerGenerationEvent.Queued -> onStatus("Queued in the shared runtime…")

        is ConsumerGenerationEvent.Prepared -> onStatus("Exact execution prepared…")

        is ConsumerGenerationEvent.Started -> onStatus("Generating locally on device…")

        is ConsumerGenerationEvent.ContentDelta -> {
            if (event.contentType == ConsumerContentType.ANSWER) onAnswerDelta(event.text)
        }

        is ConsumerGenerationEvent.Completed -> {
            if (terminal.compareAndSet(false, true)) {
                onTerminal(
                    Result.success(
                        HelloInferenceResult(
                            answer = event.answer,
                            metrics = formatMetrics(event.metrics),
                        ),
                    ),
                )
            }
        }

        is ConsumerGenerationEvent.Failed -> {
            if (terminal.compareAndSet(false, true)) {
                onTerminal(
                    Result.failure(
                        consumerFailure("generation", event.failure.code, event.failure.message),
                    ),
                )
            }
        }
    }
}

private val USE_CASE_ID = UseCaseId("generic-text-generation")

private fun formatMetrics(metrics: io.github.daniele21.localllm.contracts.ConsumerInferenceMetrics): String = buildString {
    append("TTFT: ")
    append(metrics.timeToFirstTokenMs?.let { "$it ms" } ?: "n/a")
    append(" · total: ${metrics.totalMs} ms")
    append(" · output: ${metrics.outputTokens ?: 0} tokens")
    metrics.decodeTokensPerSecond?.let { append(" · ${"%.1f".format(it)} tok/s") }
}
