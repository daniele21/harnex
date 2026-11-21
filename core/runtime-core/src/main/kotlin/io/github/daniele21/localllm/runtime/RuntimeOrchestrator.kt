package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConfigurationErrorCode
import io.github.daniele21.localllm.contracts.ContextPolicy
import io.github.daniele21.localllm.contracts.GenerationContentType
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.ModelLoadKind
import io.github.daniele21.localllm.contracts.PromptPreviewResult
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import io.github.daniele21.localllm.contracts.PrepareResult
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.RuntimeSnapshot
import io.github.daniele21.localllm.contracts.RuntimeState
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.StopReason
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.ModelProfileRegistry
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.observability.NoOpTelemetryRepository
import io.github.daniele21.localllm.observability.TelemetryRepository
import io.github.daniele21.localllm.store.ModelStore
import io.github.daniele21.localllm.store.StoredModel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun interface MonotonicClock {
    fun nowNanos(): Long
}

fun interface SeedSource {
    fun nextSeed(): Long
}

@Suppress("TooManyFunctions", "LongParameterList", "LargeClass", "LongMethod")
class RuntimeOrchestrator(
    private val registry: ModelProfileRegistry,
    private val modelStore: ModelStore,
    private val backend: InferenceBackend,
    private val scheduler: SingleDecodeScheduler = SingleDecodeScheduler(),
    private val integrityCache: ModelIntegrityCache = ModelIntegrityCache(),
    private val clock: MonotonicClock = MonotonicClock(System::nanoTime),
    private val priorityResolver: (GenerationRequest) -> DecodePriority = { DecodePriority.USER_INTERACTIVE },
    private val memoryPolicy: RuntimeMemoryPolicy = RuntimeMemoryPolicy(),
    private val memoryAwareContextPlanner: MemoryAwareContextPlanner? = null,
    private val memoryAwareModelLoadPlanner: MemoryAwareModelLoadPlanner? = null,
    private val memoryAwareEvaluationBatchContextPlanner: MemoryAwareEvaluationBatchContextPlanner? = null,
    private val enforceMemoryAdmission: Boolean = true,
    seedSource: SeedSource = SeedSource { ThreadLocalRandom.current().nextLong(MAX_SEED_EXCLUSIVE) },
    telemetryRepository: TelemetryRepository = NoOpTelemetryRepository,
    epochClock: EpochClock = EpochClock { System.currentTimeMillis() },
    private val effectivePromptSink: InferenceAuditEffectivePromptSink = InferenceAuditEffectivePromptSink { _, _ -> },
) : LocalLlmClient,
    RuntimeEvaluationBatchClient,
    AutoCloseable {
    private val resourceLock = Any()
    private val state = AtomicReference(RuntimeState.IDLE)
    private val sessions = ConcurrentHashMap<SessionId, SessionDescriptor>()
    private val closed = AtomicBoolean(false)
    private val deferredModelUnload = AtomicBoolean(false)
    private val runtimeTelemetry = RuntimeTelemetry(telemetryRepository, epochClock, enforceMemoryAdmission)
    private val generationPlanning = GenerationPlanningPolicy(seedSource)
    private val modelResidency = ModelResidencyLifecycle()

    @Volatile
    private var backendInitialized = false

    override fun runtimeSnapshot(): RuntimeSnapshot {
        val schedulerSnapshot = scheduler.snapshot()
        return RuntimeSnapshot(
            state = state.get(),
            loadedModel = modelResidency.residentModelOrNull()?.handle?.digest,
            activeSessions = sessions.size,
            queuedRequests = schedulerSnapshot.queuedRequests,
        )
    }

    override fun prepare(applicationId: ApplicationId, useCaseId: UseCaseId): PrepareResult {
        if (closed.get()) {
            return PrepareResult(false, null, "Runtime is closed")
        }
        state.set(RuntimeState.PREPARING)
        return try {
            val resolved = registry.resolve(applicationId, useCaseId)
            val model = synchronized(resourceLock) { ensureModelLoaded(resolved) }
            state.set(RuntimeState.READY)
            PrepareResult(
                ready = true,
                modelDigest = model.handle.digest,
                detail = "Model verified and loaded with ${backend.id}",
            )
        } catch (error: Throwable) {
            state.set(RuntimeState.FAILED)
            PrepareResult(
                ready = false,
                modelDigest = null,
                detail = error.message ?: "Unable to prepare local model",
            )
        }
    }

    override fun createSession(applicationId: ApplicationId, useCaseId: UseCaseId): SessionId =
        createSession(applicationId, useCaseId, SessionOptions())

    override fun createSession(applicationId: ApplicationId, useCaseId: UseCaseId, options: SessionOptions): SessionId {
        check(!closed.get()) { "Runtime is closed" }
        val resolved = registry.resolve(applicationId, useCaseId)
        return synchronized(resourceLock) {
            val requestedDigest = resolved.model.artifact.digest
            val warm = modelResidency.reusableModelOrNull()?.let { current ->
                current.handle.digest == requestedDigest && current.profileId == resolved.model.id
            } == true
            val model = ensureModelLoaded(resolved)
            val sessionId = SessionId(UUID.randomUUID().toString())
            sessions[sessionId] = SessionDescriptor(
                id = sessionId,
                applicationId = applicationId,
                useCaseId = useCaseId,
                resolved = resolved,
                model = model.handle,
                options = options,
                modelLoadDurationMs = if (warm) null else model.handle.loadDurationMs,
                modelLoadKind = if (warm) ModelLoadKind.WARM else ModelLoadKind.COLD,
            )
            state.set(RuntimeState.READY)
            sessionId
        }
    }

    /**
     * Off-main, queued with generation to keep native planning serialized with decode.
     * Returns only template/token capacity; context memory admission remains at generation.
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun previewGeneration(request: GenerationRequest): PromptPreviewResult {
        if (scheduler.isWorkerThread()) {
            return PromptPreviewResult.Rejected(LocalLlmError.Configuration("Cannot preview from a generation callback"))
        }
        if (closed.get()) return PromptPreviewResult.Rejected(LocalLlmError.Configuration("Runtime is closed"))
        val session = sessions[request.sessionId]
            ?: return PromptPreviewResult.Rejected(LocalLlmError.Configuration("Unknown technical session"))
        if (session.applicationId != request.applicationId || session.useCaseId != request.useCaseId) {
            return PromptPreviewResult.Rejected(LocalLlmError.Configuration("Preview does not match session"))
        }
        if (!session.lifecycle.tryAcquireRequest()) {
            return PromptPreviewResult.Rejected(LocalLlmError.Configuration("Session is closing"))
        }
        val response = CompletableFuture<PromptPreviewResult>()
        val previewId = RequestId("preview-" + UUID.randomUUID())
        val submitted = try {
            scheduler.submit(
                requestId = previewId,
                priority = DecodePriority.USER_INTERACTIVE,
                task = {
                    val planned = try {
                        val resolved = generationPlanning.resolveConfiguration(request, session.resolved)
                        val prompt = backend.planPrompt(
                            session.model,
                            BackendPromptPlanningRequest(
                                input = request.input,
                                systemPrompt = resolved.systemPrompt,
                                chatTemplatePolicy = session.resolved.model.chatTemplatePolicy,
                                thinkingMode = resolved.thinkingMode,
                            ),
                        )
                        val capabilities = backend.modelCapabilities(session.model)
                        generationPlanning.validateOutputConstraint(
                            request.outputConstraint, resolved, session.resolved, capabilities,
                        )
                        val context = generationPlanning.planContextSize(
                            resolvedUseCase = session.resolved,
                            options = session.options,
                            promptTokenCount = prompt.tokenCount,
                            maxOutputTokens = resolved.maxOutputTokens,
                            capabilities = capabilities,
                            preference = resolved.contextPreference,
                        )
                        PromptPreviewResult.Fits(
                            promptTokens = prompt.tokenCount,
                            contextTokens = context.requestedContextTokens,
                            maxOutputTokens = resolved.maxOutputTokens,
                            chatTemplateSource = prompt.chatTemplateSource,
                        )
                    } catch (error: Throwable) {
                        PromptPreviewResult.Rejected(
                            when (error) {
                                is GenerationPlanningException ->
                                    LocalLlmError.Configuration(error.message.orEmpty(), error.reason)
                                is BackendException -> error.toPublicError()
                                else -> LocalLlmError.NativeRuntime("Prompt preview failed")
                            },
                        )
                    }
                    response.complete(planned)
                },
                onQueuedCancellation = { response.complete(PromptPreviewResult.Rejected(LocalLlmError.Cancelled())) },
                onRunningCancellation = { response.complete(PromptPreviewResult.Rejected(LocalLlmError.Cancelled())) },
                onFinished = { releaseSessionRequest(session) },
            )
        } catch (_: Throwable) {
            releaseSessionRequest(session)
            return PromptPreviewResult.Rejected(LocalLlmError.NativeRuntime("Prompt preview could not be scheduled"))
        }
        return try {
            response.get(30L, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            submitted.handle.cancel()
            PromptPreviewResult.Rejected(LocalLlmError.NativeRuntime("Prompt preview timed out"))
        } catch (_: InterruptedException) {
            submitted.handle.cancel()
            Thread.currentThread().interrupt()
            PromptPreviewResult.Rejected(LocalLlmError.Cancelled())
        } catch (_: java.util.concurrent.ExecutionException) {
            submitted.handle.cancel()
            PromptPreviewResult.Rejected(LocalLlmError.NativeRuntime("Prompt preview failed"))
        }
    }

    @Suppress("ReturnCount")
    override fun generate(request: GenerationRequest, listener: GenerationListener): GenerationHandle {
        if (closed.get()) {
            return failImmediately(request.requestId, listener, LocalLlmError.Configuration("Runtime is closed"))
        }
        val session = sessions[request.sessionId]
            ?: return failImmediately(
                request.requestId,
                listener,
                LocalLlmError.Configuration("Unknown session ${request.sessionId.value}"),
            )
        if (session.applicationId != request.applicationId || session.useCaseId != request.useCaseId) {
            return failImmediately(
                request.requestId,
                listener,
                LocalLlmError.Configuration("Generation request does not match its session binding"),
            )
        }
        if (!session.lifecycle.tryAcquireRequest()) {
            return failImmediately(
                request.requestId,
                listener,
                LocalLlmError.Configuration("Session ${request.sessionId.value} is closing"),
            )
        }

        val lifecycle = RequestLifecycle(
            requestId = request.requestId,
            listener = listener,
            onTerminal = { releaseSessionRequest(session) },
        )
        val enqueuedAt = clock.nowNanos()
        runtimeTelemetry.queued(request, session.model.digest)

        val submission = try {
            scheduler.submit(
                requestId = request.requestId,
                priority = priorityResolver(request),
                task = { executeGeneration(request, session, lifecycle, enqueuedAt) },
                onQueuedCancellation = {
                    lifecycle.requestCancellation()
                    val cancellation = LocalLlmError.Cancelled("Generation cancelled before execution")
                    runtimeTelemetry.failed(request.requestId, cancellation)
                    lifecycle.finish(GenerationEvent.Failed(request.requestId, cancellation))
                },
                onRunningCancellation = {
                    lifecycle.requestCancellation()
                    runCatching { backend.cancel(request.requestId.value) }
                },
                onFinished = lifecycle::schedulerFinished,
                onQueued = { position ->
                    runtimeTelemetry.queuedPosition(request.requestId, position)
                    lifecycle.emit(GenerationEvent.Queued(request.requestId, position))
                },
            )
        } catch (error: Throwable) {
            val failure = LocalLlmError.Configuration(error.message ?: "Unable to schedule generation")
            runtimeTelemetry.failed(request.requestId, failure)
            lifecycle.finish(GenerationEvent.Failed(request.requestId, failure))
            lifecycle.schedulerFinished()
            return NoOpGenerationHandle(request.requestId)
        }

        return RuntimeGenerationHandle(
            requestId = request.requestId,
            schedulerHandle = submission.handle,
            lifecycle = lifecycle,
        )
    }

    @Suppress("ReturnCount")
    override fun generateEvaluationBatch(
        request: RuntimeEvaluationBatchRequest,
        listener: RuntimeEvaluationBatchListener,
    ): RuntimeEvaluationBatchHandle {
        if (closed.get()) {
            return failEvaluationBatchImmediately(request.batchId, listener, LocalLlmError.Configuration("Runtime is closed"))
        }
        val batchBackend = backend as? EvaluationBatchInferenceBackend
            ?: return failEvaluationBatchImmediately(
                request.batchId,
                listener,
                LocalLlmError.Configuration("Backend ${backend.id} does not support evaluation batching"),
            )
        val bindings = try {
            acquireEvaluationBatchBindings(request)
        } catch (error: Throwable) {
            return failEvaluationBatchImmediately(
                request.batchId,
                listener,
                LocalLlmError.Configuration(error.message ?: "Invalid evaluation batch binding"),
            )
        }
        val lifecycle = RuntimeEvaluationBatchLifecycle(
            requestIds = request.requests.map(GenerationRequest::requestId).toSet(),
            onTerminal = { bindings.forEach { releaseSessionRequest(it.session) } },
        )
        val enqueuedAt = clock.nowNanos()
        bindings.forEach { binding -> runtimeTelemetry.queued(binding.request, binding.session.model.digest) }

        val submission = try {
            scheduler.submit(
                requestId = request.batchId,
                priority = DecodePriority.BACKGROUND,
                task = {
                    if (!lifecycle.markRunning()) return@submit
                    executeEvaluationBatch(
                        bindings = bindings,
                        lifecycle = lifecycle,
                        listener = listener,
                        batchBackend = batchBackend,
                        enqueuedAt = enqueuedAt,
                    )
                },
                onQueuedCancellation = {
                    lifecycle.requestCancellation()
                    failEvaluationBatch(
                        bindings,
                        lifecycle,
                        listener,
                        LocalLlmError.Cancelled("Evaluation batch cancelled before execution"),
                    )
                },
                onRunningCancellation = {
                    lifecycle.requestCancellation()
                    if (lifecycle.isRunning()) {
                        bindings.forEach { binding ->
                            runCatching { batchBackend.cancelEvaluationCase(binding.request.requestId.value) }
                        }
                    } else {
                        failEvaluationBatch(
                            bindings,
                            lifecycle,
                            listener,
                            LocalLlmError.Cancelled("Evaluation batch cancelled before execution"),
                        )
                    }
                },
                onQueued = { position ->
                    bindings.forEach { binding -> runtimeTelemetry.queuedPosition(binding.request.requestId, position) }
                },
            )
        } catch (error: Throwable) {
            failEvaluationBatch(
                bindings,
                lifecycle,
                listener,
                LocalLlmError.Configuration(error.message ?: "Unable to schedule evaluation batch"),
            )
            return NoOpRuntimeEvaluationBatchHandle(request.batchId)
        }

        return ScheduledRuntimeEvaluationBatchHandle(
            batchId = request.batchId,
            schedulerHandle = submission.handle,
            lifecycle = lifecycle,
            cancelRunningCase = { requestId -> batchBackend.cancelEvaluationCase(requestId.value) },
        )
    }

    override fun closeSession(sessionId: SessionId) {
        val session = sessions[sessionId] ?: return
        session.lifecycle.beginClose()
        if (session.lifecycle.isReleaseReady()) {
            releaseSession(session)
        }
    }

    fun memoryResourceSnapshot(): RuntimeMemoryResourceSnapshot {
        val schedulerSnapshot = scheduler.snapshot()
        return RuntimeMemoryResourceSnapshot(
            modelLoaded = modelResidency.hasResidentModel(),
            activeSessions = sessions.size,
            activeGeneration = schedulerSnapshot.activeRequest != null,
            queuedGenerations = schedulerSnapshot.queuedRequests,
        )
    }

    fun handleMemoryPressure(pressure: RuntimeMemoryPressure): RuntimeMemoryResult {
        val action = memoryPolicy.decide(pressure, memoryResourceSnapshot())
        return when (action) {
            RuntimeMemoryAction.NONE -> RuntimeMemoryResult(action, 0, false, false)

            RuntimeMemoryAction.UNLOAD_IDLE_MODEL -> {
                val unloaded = unloadIdleModel()
                RuntimeMemoryResult(action, 0, unloaded, deferred = !unloaded)
            }

            RuntimeMemoryAction.CANCEL_AND_RELEASE_ALL -> releaseForCriticalMemory(action)
        }
    }

    fun unloadIdleModel(): Boolean = synchronized(resourceLock) {
        val schedulerSnapshot = scheduler.snapshot()
        if (sessions.isNotEmpty() || schedulerSnapshot.activeRequest != null || schedulerSnapshot.queuedRequests != 0) {
            return@synchronized false
        }
        if (!unloadResidentModel()) {
            return@synchronized false
        }
        state.set(RuntimeState.IDLE)
        true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        deferredModelUnload.set(true)
        scheduler.snapshot().activeRequest?.let(scheduler::cancel)
        scheduler.close()
        sessions.values.forEach { session ->
            session.lifecycle.beginClose()
            if (session.lifecycle.isReleaseReady()) {
                releaseSession(session)
            }
        }
        val finalized = attemptDeferredModelUnload(ignoreActiveGeneration = false)
        integrityCache.clear()
        if (!finalized) {
            state.set(RuntimeState.DEGRADED)
        }
    }

    private fun executeGeneration(request: GenerationRequest, session: SessionDescriptor, lifecycle: RequestLifecycle, enqueuedAt: Long) {
        if (lifecycle.isCancellationRequested()) {
            failGeneration(request.requestId, lifecycle, LocalLlmError.Cancelled())
            return
        }

        try {
            val prepared = prepareGeneration(request, session, lifecycle)
            val streamed = streamGeneration(request, session, lifecycle, prepared)
            finalizeGeneration(request, session, lifecycle, enqueuedAt, prepared, streamed)
        } catch (error: Throwable) {
            failGeneration(request.requestId, lifecycle, generationFailure(error, lifecycle))
        } finally {
            settleRuntimeStateAfterExecution()
        }
    }

    private fun prepareGeneration(
        request: GenerationRequest,
        session: SessionDescriptor,
        lifecycle: RequestLifecycle,
    ): PreparedGeneration {
        val executionStartedAt = clock.nowNanos()
        val resolved = generationPlanning.resolveConfiguration(request, session.resolved)
        lifecycle.ensureNotCancelled()
        val planningStartedAt = clock.nowNanos()
        val promptPlan = backend.planPrompt(
            session.model,
            BackendPromptPlanningRequest(
                input = request.input,
                systemPrompt = resolved.systemPrompt,
                chatTemplatePolicy = session.resolved.model.chatTemplatePolicy,
                thinkingMode = resolved.thinkingMode,
            ),
        )
        lifecycle.ensureNotCancelled()
        val promptPlanningMs = nanosToMillis(clock.nowNanos() - planningStartedAt)
        val capabilities = backend.modelCapabilities(session.model)
        lifecycle.ensureNotCancelled()
        generationPlanning.validateOutputConstraint(
            outputConstraint = request.outputConstraint,
            resolved = resolved,
            resolvedUseCase = session.resolved,
            capabilities = capabilities,
        )
        val contextPlan = generationPlanning.planContextSize(
            resolvedUseCase = session.resolved,
            options = session.options,
            promptTokenCount = promptPlan.tokenCount,
            maxOutputTokens = resolved.maxOutputTokens,
            capabilities = capabilities,
            preference = resolved.contextPreference,
        )
        lifecycle.ensureNotCancelled()
        val contextResult = materializeContext(session, contextPlan)
        if (lifecycle.isCancellationRequested()) {
            releaseCancelledMaterialization(session, contextResult)
            throw GenerationCancelledException()
        }
        val executionEvidence = backend.executionEvidence(contextResult.context)
        val effective = resolved.effectiveMetadata(promptPlan, contextResult.context.contextSize)
        effectivePromptSink.publish(request.requestId, promptPlan.prompt)
        runtimeTelemetry.prepared(
            requestId = request.requestId,
            configuration = effective,
            promptPlanningMs = promptPlanningMs,
            contextCreationMs = contextResult.creationMs,
            executionEvidence = executionEvidence,
        )
        lifecycle.emit(GenerationEvent.Prepared(request.requestId, session.model.digest, effective))
        return PreparedGeneration(resolved, promptPlan, contextResult, capabilities, executionStartedAt, promptPlanningMs)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun streamGeneration(
        request: GenerationRequest,
        session: SessionDescriptor,
        lifecycle: RequestLifecycle,
        prepared: PreparedGeneration,
    ): StreamedGeneration {
        val resolved = prepared.resolved
        val promptPlan = prepared.promptPlan
        val contextResult = prepared.contextResult
        val capabilities = prepared.capabilities
        val firstTokenAt = AtomicLong(0)
        val firstAnswerAt = AtomicLong(0)
        val rawOutput = StringBuilder()
        val reasoningOutput = StringBuilder()
        val answerOutput = StringBuilder()
        val streamProtocol = (resolved.preset?.generation ?: session.resolved.useCase.generationDefaults).reasoningStreamProtocol
        val reasoningControl = generationPlanning.resolveReasoningControl(
            thinkingMode = resolved.thinkingMode,
            guardPolicy = resolved.guardPolicy,
            streamProtocol = streamProtocol,
            maxOutputTokens = resolved.maxOutputTokens,
            capabilities = capabilities,
        )
        val streamParser = ReasoningStreamParser(resolved.thinkingMode, streamProtocol)
        var lastGeneratedTokens = 0
        state.set(RuntimeState.GENERATING)
        runtimeTelemetry.started(request.requestId)
        lifecycle.emit(GenerationEvent.Started(request.requestId, session.model.digest))
        val generationGuard = GenerationGuard(
            thinkingMode = resolved.thinkingMode,
            policy = resolved.guardPolicy,
            thinkingCloseMarker = streamProtocol.closeMarker,
            enforceThinkingBudget = reasoningControl == null,
        )
        val guardStopReason = AtomicReference<StopReason?>(null)
        val emitParsedChunk: (ParsedGenerationChunk, Int) -> Unit = { parsed, generatedTokens ->
            when (parsed.contentType) {
                GenerationContentType.REASONING -> reasoningOutput.append(parsed.text)

                GenerationContentType.ANSWER -> {
                    answerOutput.append(parsed.text)
                    if (parsed.text.isNotBlank()) {
                        firstAnswerAt.compareAndSet(0, clock.nowNanos())
                    }
                }
            }
            lifecycle.emit(
                GenerationEvent.TextDelta(
                    requestId = request.requestId,
                    text = parsed.text,
                    generatedTokens = generatedTokens,
                    contentType = parsed.contentType,
                ),
            )
        }
        val backendRequest = resolved.backendRequest(request, promptPlan, reasoningControl)
        lifecycle.ensureNotCancelled()
        val outcome = backend.generate(contextResult.context, backendRequest) { text, generatedTokens ->
            if (lifecycle.isCancellationRequested()) {
                false
            } else {
                firstTokenAt.compareAndSet(0, clock.nowNanos())
                lastGeneratedTokens = generatedTokens
                rawOutput.append(text)
                streamParser.accept(text).forEach { emitParsedChunk(it, generatedTokens) }
                val guardReason = generationGuard.observe(text, generatedTokens)
                if (guardReason != null) guardStopReason.compareAndSet(null, guardReason)
                guardReason == null && !lifecycle.isCancellationRequested()
            }
        }
        if (!lifecycle.isCancellationRequested()) {
            streamParser.finish().forEach { emitParsedChunk(it, lastGeneratedTokens) }
        }
        return StreamedGeneration(
            outcome = outcome,
            guardReason = guardStopReason.get(),
            output = rawOutput.toString(),
            reasoningOutput = reasoningOutput.toString(),
            answerOutput = answerOutput.toString(),
            firstTokenAt = firstTokenAt,
            firstAnswerAt = firstAnswerAt,
        )
    }

    private fun finalizeGeneration(
        request: GenerationRequest,
        session: SessionDescriptor,
        lifecycle: RequestLifecycle,
        enqueuedAt: Long,
        prepared: PreparedGeneration,
        streamed: StreamedGeneration,
    ) {
        val guardReason = streamed.guardReason
        // Explicit cancellation wins; a guard stop remains successful even if the backend reports cancellation.
        if (lifecycle.isCancellationRequested() || (guardReason == null && streamed.outcome is BackendGenerationOutcome.Cancelled)) {
            failGeneration(request.requestId, lifecycle, LocalLlmError.Cancelled())
            return
        }
        val metrics = streamed.outcome.metrics()
        completeGeneration(
            request = request,
            session = session,
            lifecycle = lifecycle,
            output = streamed.output,
            reasoningOutput = streamed.reasoningOutput,
            answerOutput = streamed.answerOutput,
            backendMetrics = if (guardReason != null) metrics.copy(stopReason = guardReason) else metrics,
            executionStartedAt = prepared.executionStartedAt,
            enqueuedAt = enqueuedAt,
            firstTokenAt = streamed.firstTokenAt,
            firstAnswerAt = streamed.firstAnswerAt,
            promptPlanningMs = prepared.promptPlanningMs,
            contextCreationMs = prepared.contextResult.creationMs,
        )
    }

    private fun generationFailure(error: Throwable, lifecycle: RequestLifecycle): LocalLlmError = when (error) {
        is GenerationCancelledException -> LocalLlmError.Cancelled()
        is GenerationPlanningException -> LocalLlmError.Configuration(error.message.orEmpty(), error.reason)
        is BackendException -> if (lifecycle.isCancellationRequested()) LocalLlmError.Cancelled() else error.toPublicError()
        else -> LocalLlmError.NativeRuntime(error.message ?: "Unexpected local inference failure")
    }

    private fun failGeneration(requestId: RequestId, lifecycle: RequestLifecycle, error: LocalLlmError) {
        runtimeTelemetry.failed(requestId, error)
        lifecycle.finish(GenerationEvent.Failed(requestId, error))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun executeEvaluationBatch(
        bindings: List<BatchSessionBinding>,
        lifecycle: RuntimeEvaluationBatchLifecycle,
        listener: RuntimeEvaluationBatchListener,
        batchBackend: EvaluationBatchInferenceBackend,
        enqueuedAt: Long,
    ) {
        if (lifecycle.isCancellationRequested()) {
            failEvaluationBatch(bindings, lifecycle, listener, LocalLlmError.Cancelled())
            return
        }

        var context: BackendEvaluationBatchContextHandle? = null
        var contextReleaseFailed = false
        try {
            val executionStartedAt = clock.nowNanos()
            val prepared = bindings.map { binding -> prepareEvaluationBatchCase(binding) }
            ensureEvaluationBatchNotCancelled(lifecycle)
            val perSequenceContextSize = resolveEvaluationBatchContextSize(bindings, prepared)
            val contextStartedAt = clock.nowNanos()
            context = synchronized(resourceLock) {
                batchBackend.createEvaluationBatchContext(
                    model = bindings.first().session.model,
                    profile = bindings.first().session.resolved.model,
                    configuration = BackendEvaluationBatchContextConfiguration(
                        perSequenceContextSize = perSequenceContextSize,
                        maxSequences = bindings.size,
                    ),
                )
            }
            val contextCreationMs = nanosToMillis(clock.nowNanos() - contextStartedAt)
            ensureEvaluationBatchNotCancelled(lifecycle)
            val executionEvidence = backend.executionEvidence(context)
            prepared.forEach { preparedCase ->
                val effective = preparedCase.resolved.effectiveMetadata(preparedCase.promptPlan, perSequenceContextSize)
                runtimeTelemetry.prepared(
                    requestId = preparedCase.binding.request.requestId,
                    configuration = effective,
                    promptPlanningMs = preparedCase.promptPlanningMs,
                    contextCreationMs = contextCreationMs,
                    executionEvidence = executionEvidence,
                )
            }
            state.set(RuntimeState.GENERATING)
            prepared.forEach { runtimeTelemetry.started(it.binding.request.requestId) }
            val backendResult = batchBackend.generateEvaluationBatch(
                context = context,
                requests = prepared.map(PreparedEvaluationBatchCase::backendRequest),
            )
            ensureEvaluationBatchNotCancelled(lifecycle)
            validateEvaluationBatchResultOrder(prepared, backendResult)
            val totalMs = nanosToMillis(clock.nowNanos() - enqueuedAt)
            val queueMs = nanosToMillis(executionStartedAt - enqueuedAt)
            val results = backendResult.cases.zip(prepared).map { (backendCase, preparedCase) ->
                val metrics = backendCase.outcome.metrics().toPublicMetrics(
                    queueMs = queueMs,
                    modelLoadMs = preparedCase.binding.session.modelLoadDurationMs,
                    modelLoadKind = preparedCase.binding.session.modelLoadKind,
                    timeToFirstTokenMs = null,
                    timeToFirstAnswerMs = null,
                    totalMs = totalMs,
                    promptPlanningMs = preparedCase.promptPlanningMs,
                    contextCreationMs = contextCreationMs,
                )
                when (backendCase.outcome) {
                    is BackendGenerationOutcome.Completed -> RuntimeEvaluationBatchCaseResult.Completed(
                        requestId = preparedCase.binding.request.requestId,
                        output = backendCase.output,
                        metrics = metrics,
                    )

                    is BackendGenerationOutcome.Cancelled -> RuntimeEvaluationBatchCaseResult.Cancelled(
                        requestId = preparedCase.binding.request.requestId,
                        metrics = metrics,
                    )
                }
            }
            val releasing = context
            context = null
            val release = runCatching { releaseEvaluationBatchContext(batchBackend, releasing) }
            contextReleaseFailed = release.isFailure
            release.getOrThrow()
            results.forEach { result ->
                when (result) {
                    is RuntimeEvaluationBatchCaseResult.Completed -> runtimeTelemetry.completed(result.requestId, result.metrics)

                    is RuntimeEvaluationBatchCaseResult.Cancelled -> {
                        runtimeTelemetry.failed(result.requestId, LocalLlmError.Cancelled())
                    }
                }
            }
            lifecycle.finish(listener, RuntimeEvaluationBatchOutcome.Completed(results))
        } catch (error: Throwable) {
            context?.let { activeContext ->
                context = null
                runCatching { releaseEvaluationBatchContext(batchBackend, activeContext) }
                    .onFailure { contextReleaseFailed = true }
            }
            val failure = when (error) {
                is GenerationCancelledException -> LocalLlmError.Cancelled()
                is GenerationPlanningException -> LocalLlmError.Configuration(error.message.orEmpty(), error.reason)
                is BackendException -> error.toPublicError()
                else -> LocalLlmError.NativeRuntime(error.message ?: "Unexpected evaluation batch failure")
            }
            failEvaluationBatch(bindings, lifecycle, listener, failure)
        } finally {
            settleRuntimeStateAfterExecution(contextReleaseFailed)
        }
    }

    /** Do not overwrite a native/session release failure reported during terminal cleanup. */
    private fun settleRuntimeStateAfterExecution(contextReleaseFailed: Boolean = false) {
        val unloaded = attemptDeferredModelUnload(ignoreActiveGeneration = true)
        if (contextReleaseFailed) {
            state.set(RuntimeState.DEGRADED)
        } else if (!closed.get() && !unloaded) {
            state.updateAndGet { observed ->
                if (observed == RuntimeState.DEGRADED || deferredModelUnload.get()) RuntimeState.DEGRADED else RuntimeState.READY
            }
        }
    }

    private fun ensureEvaluationBatchNotCancelled(lifecycle: RuntimeEvaluationBatchLifecycle) {
        if (lifecycle.isCancellationRequested()) throw GenerationCancelledException()
    }

    private fun validateEvaluationBatchResultOrder(
        prepared: List<PreparedEvaluationBatchCase>,
        backendResult: BackendEvaluationBatchResult,
    ) {
        val expectedRequestIds = prepared.map { it.binding.request.requestId.value }
        if (backendResult.cases.map(BackendEvaluationBatchCaseResult::requestId) != expectedRequestIds) {
            throw BackendException(
                "EVALUATION_BATCH_ATTRIBUTION",
                "Evaluation batch backend result order does not match the submitted request order",
            )
        }
    }

    private fun acquireEvaluationBatchBindings(request: RuntimeEvaluationBatchRequest): List<BatchSessionBinding> {
        val acquired = ArrayList<BatchSessionBinding>(request.requests.size)
        try {
            request.requests.forEach { generationRequest ->
                val session = sessions[generationRequest.sessionId]
                    ?: error("Unknown session ${generationRequest.sessionId.value}")
                check(
                    session.applicationId == generationRequest.applicationId &&
                        session.useCaseId == generationRequest.useCaseId,
                ) { "Evaluation batch request does not match its session binding" }
                check(session.options.kind == SessionKind.STATELESS) {
                    "Evaluation batching requires stateless isolated sessions"
                }
                check(session.context == null) {
                    "Evaluation batching requires sessions without an ordinary materialized context"
                }
                check(session.lifecycle.tryAcquireRequest()) {
                    "Session ${generationRequest.sessionId.value} is closing"
                }
                acquired += BatchSessionBinding(generationRequest, session)
            }
            validateEvaluationBatchBindings(acquired)
            return acquired
        } catch (error: Throwable) {
            acquired.forEach { binding -> releaseSessionRequest(binding.session) }
            throw error
        }
    }

    private fun validateEvaluationBatchBindings(bindings: List<BatchSessionBinding>) {
        val first = bindings.first()
        bindings.forEach { binding ->
            check(binding.session.applicationId == first.session.applicationId) {
                "Evaluation batch cases must share one application binding"
            }
            check(binding.session.useCaseId == first.session.useCaseId) {
                "Evaluation batch cases must share one use-case binding"
            }
            check(binding.session.model.digest == first.session.model.digest) {
                "Evaluation batch cases must use one resident model artifact"
            }
            check(binding.session.resolved.model.id == first.session.resolved.model.id) {
                "Evaluation batch cases must use one model profile"
            }
            check(binding.session.options == first.session.options) {
                "Evaluation batch cases must use identical session options"
            }
        }
        val resident = modelResidency.reusableModelOrNull()
        check(resident != null && resident.handle.digest == first.session.model.digest) {
            "Evaluation batch requires the selected model to remain resident"
        }
    }

    private fun prepareEvaluationBatchCase(binding: BatchSessionBinding): PreparedEvaluationBatchCase {
        val resolved = generationPlanning.resolveConfiguration(binding.request, binding.session.resolved)
        if (resolved.thinkingMode != ThinkingMode.DISABLED) {
            throw GenerationPlanningException(
                ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION,
                "Evaluation batching currently requires thinking mode to be disabled",
            )
        }
        val planningStartedAt = clock.nowNanos()
        val promptPlan = backend.planPrompt(
            binding.session.model,
            BackendPromptPlanningRequest(
                input = binding.request.input,
                systemPrompt = resolved.systemPrompt,
                chatTemplatePolicy = binding.session.resolved.model.chatTemplatePolicy,
                thinkingMode = resolved.thinkingMode,
            ),
        )
        val promptPlanningMs = nanosToMillis(clock.nowNanos() - planningStartedAt)
        val capabilities = backend.modelCapabilities(binding.session.model)
        generationPlanning.validateOutputConstraint(
            outputConstraint = binding.request.outputConstraint,
            resolved = resolved,
            resolvedUseCase = binding.session.resolved,
            capabilities = capabilities,
        )
        val contextPlan = generationPlanning.planContextSize(
            resolvedUseCase = binding.session.resolved,
            options = binding.session.options,
            promptTokenCount = promptPlan.tokenCount,
            maxOutputTokens = resolved.maxOutputTokens,
            capabilities = capabilities,
            preference = resolved.contextPreference,
        )
        return PreparedEvaluationBatchCase(
            binding = binding,
            resolved = resolved,
            promptPlan = promptPlan,
            contextPlan = contextPlan,
            promptPlanningMs = promptPlanningMs,
            backendRequest = resolved.backendRequest(binding.request, promptPlan, null),
        )
    }

    private fun resolveEvaluationBatchContextSize(bindings: List<BatchSessionBinding>, prepared: List<PreparedEvaluationBatchCase>): Int {
        val requestedPerSequence = prepared.maxOf { it.contextPlan.requestedContextTokens }
        val minimumPerSequence = prepared.maxOf { it.contextPlan.minimumRequiredTokens }
        val sequenceCount = bindings.size
        val requestedAggregate = aggregateContextTokens(requestedPerSequence, sequenceCount)
        val planner = memoryAwareEvaluationBatchContextPlanner
        if (planner == null) {
            if (memoryAwareContextPlanner != null) {
                throw GenerationPlanningException(
                    ConfigurationErrorCode.MEMORY_BUDGET_EXCEEDED,
                    "Evaluation batch aggregate memory planner is required when memory-aware context admission is enabled",
                )
            }
            return requestedPerSequence
        }
        val firstSession = bindings.first().session
        val approvedTiers = when (val policy = firstSession.options.contextPolicy) {
            is ContextPolicy.Manual -> listOf(policy.tokens)

            ContextPolicy.Auto -> firstSession.resolved.model.runtimeCapabilities.approvedContextTiers.ifEmpty {
                ContextSizeSelector.supportedSizes
            }
        }
        val schedulerSnapshot = scheduler.snapshot()
        val decision = planner.plan(
            MemoryAwareEvaluationBatchContextRequest(
                modelProfileId = firstSession.resolved.model.id,
                requestedPerSequenceContextTokens = requestedPerSequence,
                minimumPerSequenceContextTokens = minimumPerSequence,
                approvedPerSequenceContextTiers = approvedTiers,
                sequenceCount = sequenceCount,
                residency = RuntimeResidencySnapshot(
                    modelLoaded = modelResidency.hasResidentModel(),
                    residentContexts = sessions.values.count { it.context != null },
                    activeGeneration = schedulerSnapshot.activeRequest != null,
                    queuedGenerations = schedulerSnapshot.queuedRequests,
                ),
            ),
        )
        return when (decision) {
            is MemoryAwareEvaluationBatchContextDecision.Allow -> {
                runtimeTelemetry.memoryAdmission(
                    resource = MemoryAdmissionResource.CONTEXT,
                    outcome = if (decision.downshifted) MemoryAdmissionOutcome.DOWNSHIFT else MemoryAdmissionOutcome.ALLOW,
                    estimate = decision.estimate,
                    requestedContextTokens = requestedAggregate,
                    effectiveContextTokens = decision.aggregateContextTokens,
                )
                if (enforceMemoryAdmission) decision.perSequenceContextTokens else requestedPerSequence
            }

            is MemoryAwareEvaluationBatchContextDecision.Reject -> {
                runtimeTelemetry.memoryAdmission(
                    resource = MemoryAdmissionResource.CONTEXT,
                    outcome = MemoryAdmissionOutcome.REJECT,
                    decisionReason = decision.reason.name,
                    admissionReason = decision.admissionReason,
                    requestedContextTokens = requestedAggregate,
                )
                if (enforceMemoryAdmission) {
                    throw GenerationPlanningException(
                        ConfigurationErrorCode.MEMORY_BUDGET_EXCEEDED,
                        evaluationBatchMemoryFailureMessage(requestedAggregate, decision),
                    )
                }
                requestedPerSequence
            }
        }
    }

    private fun aggregateContextTokens(perSequenceTokens: Int, sequenceCount: Int): Int = try {
        Math.multiplyExact(perSequenceTokens, sequenceCount)
    } catch (_: ArithmeticException) {
        throw GenerationPlanningException(
            ConfigurationErrorCode.MEMORY_BUDGET_EXCEEDED,
            "Evaluation batch aggregate context size overflow",
        )
    }

    private fun evaluationBatchMemoryFailureMessage(
        requestedAggregate: Int,
        decision: MemoryAwareEvaluationBatchContextDecision.Reject,
    ): String = buildString {
        append("Memory admission rejected evaluation batch aggregate context ")
        append(requestedAggregate)
        append(" tokens: ")
        append(decision.reason.name)
        decision.admissionReason?.let { reason ->
            append(" (")
            append(reason.name)
            append(')')
        }
    }

    private fun releaseEvaluationBatchContext(
        batchBackend: EvaluationBatchInferenceBackend,
        context: BackendEvaluationBatchContextHandle,
    ) {
        synchronized(resourceLock) {
            batchBackend.releaseEvaluationBatchContext(context)
        }
    }

    private fun failEvaluationBatch(
        bindings: List<BatchSessionBinding>,
        lifecycle: RuntimeEvaluationBatchLifecycle,
        listener: RuntimeEvaluationBatchListener,
        error: LocalLlmError,
    ) {
        bindings.forEach { binding -> runtimeTelemetry.failed(binding.request.requestId, error) }
        lifecycle.finish(listener, RuntimeEvaluationBatchOutcome.Failed(error))
    }

    private fun failEvaluationBatchImmediately(
        batchId: RequestId,
        listener: RuntimeEvaluationBatchListener,
        error: LocalLlmError,
    ): RuntimeEvaluationBatchHandle {
        runCatching { listener.onTerminal(RuntimeEvaluationBatchOutcome.Failed(error)) }
        return NoOpRuntimeEvaluationBatchHandle(batchId)
    }

    private fun materializeContext(session: SessionDescriptor, contextPlan: ContextPlanningResult): ContextMaterialization =
        synchronized(resourceLock) {
            val requestedContextSize = contextPlan.requestedContextTokens
            val current = session.context
            val capabilities = session.resolved.model.runtimeCapabilities
            val statelessReuse = session.resolved.useCase.cachePolicy.reuseStatelessContext &&
                capabilities.supportsStatelessContextReuse
            val liveReuseAllowed = session.options.kind == SessionKind.CONVERSATIONAL || statelessReuse
            if (current != null && current.contextSize >= requestedContextSize && liveReuseAllowed) {
                return@synchronized ContextMaterialization(current, null, created = false)
            }
            if (current != null && session.options.kind == SessionKind.CONVERSATIONAL) {
                throw GenerationPlanningException(
                    ConfigurationErrorCode.CONTEXT_RECONFIGURATION_REQUIRED,
                    "Conversational context growth requires an explicit new session",
                )
            }
            if (current != null) {
                backend.releaseContext(current)
                session.context = null
            }
            val admittedContextSize = resolveMemoryAwareContextSize(session, contextPlan)
            val startedAt = clock.nowNanos()
            val created = backend.createContext(
                model = session.model,
                profile = session.resolved.model,
                configuration = BackendContextConfiguration(admittedContextSize),
            )
            session.context = created
            ContextMaterialization(created, nanosToMillis(clock.nowNanos() - startedAt), created = true)
        }

    private fun resolveMemoryAwareContextSize(session: SessionDescriptor, contextPlan: ContextPlanningResult): Int {
        val planner = memoryAwareContextPlanner ?: return contextPlan.requestedContextTokens
        val schedulerSnapshot = scheduler.snapshot()
        val decision = planner.plan(
            MemoryAwareContextRequest(
                modelProfileId = session.resolved.model.id,
                requestedContextTokens = contextPlan.requestedContextTokens,
                minimumContextTokens = contextPlan.minimumRequiredTokens,
                approvedContextTiers = contextPlan.memoryEligibleContextTiers,
                residency = RuntimeResidencySnapshot(
                    modelLoaded = modelResidency.hasResidentModel(),
                    residentContexts = sessions.values.count { it.context != null },
                    activeGeneration = schedulerSnapshot.activeRequest != null,
                    queuedGenerations = schedulerSnapshot.queuedRequests,
                ),
            ),
        )
        return when (decision) {
            is MemoryAwareContextDecision.Allow -> {
                runtimeTelemetry.memoryAdmission(
                    resource = MemoryAdmissionResource.CONTEXT,
                    outcome = if (decision.downshifted) MemoryAdmissionOutcome.DOWNSHIFT else MemoryAdmissionOutcome.ALLOW,
                    estimate = decision.estimate,
                    requestedContextTokens = contextPlan.requestedContextTokens,
                    effectiveContextTokens = decision.contextTokens,
                )
                if (enforceMemoryAdmission) decision.contextTokens else contextPlan.requestedContextTokens
            }

            is MemoryAwareContextDecision.Reject -> {
                runtimeTelemetry.memoryAdmission(
                    resource = MemoryAdmissionResource.CONTEXT,
                    outcome = MemoryAdmissionOutcome.REJECT,
                    decisionReason = decision.reason.name,
                    admissionReason = decision.admissionReason,
                    requestedContextTokens = contextPlan.requestedContextTokens,
                )
                if (enforceMemoryAdmission) {
                    throw GenerationPlanningException(
                        ConfigurationErrorCode.MEMORY_BUDGET_EXCEEDED,
                        memoryAdmissionFailureMessage(contextPlan, decision),
                    )
                }
                contextPlan.requestedContextTokens
            }
        }
    }

    private fun memoryAdmissionFailureMessage(contextPlan: ContextPlanningResult, decision: MemoryAwareContextDecision.Reject): String =
        buildString {
            append("Memory admission rejected context ")
            append(contextPlan.requestedContextTokens)
            append(" tokens: ")
            append(decision.reason.name)
            decision.admissionReason?.let { reason ->
                append(" (")
                append(reason.name)
                append(')')
            }
        }

    private fun releaseCancelledMaterialization(session: SessionDescriptor, materialization: ContextMaterialization) {
        if (!materialization.created) return
        synchronized(resourceLock) {
            if (session.context === materialization.context) {
                backend.releaseContext(materialization.context)
                session.context = null
            }
        }
    }

    private fun BackendException.toPublicError(): LocalLlmError {
        val reason = when (code) {
            "CHAT_TEMPLATE_UNAVAILABLE" -> ConfigurationErrorCode.CHAT_TEMPLATE_UNAVAILABLE
            "CHAT_TEMPLATE_UNSUPPORTED" -> ConfigurationErrorCode.CHAT_TEMPLATE_UNSUPPORTED
            "TOKENIZATION_FAILED" -> ConfigurationErrorCode.PROMPT_TOKENIZATION_FAILED
            "CONTEXT_OVERFLOW" -> ConfigurationErrorCode.CONTEXT_CAPACITY_EXCEEDED
            "OUTPUT_CONSTRAINT_UNSUPPORTED" -> ConfigurationErrorCode.OUTPUT_CONSTRAINT_UNSUPPORTED
            "INVALID_OUTPUT_CONSTRAINT" -> ConfigurationErrorCode.INVALID_OUTPUT_CONSTRAINT
            else -> null
        }
        return if (reason == null) {
            LocalLlmError.NativeRuntime("$code: ${message.orEmpty()}")
        } else {
            LocalLlmError.Configuration(message.orEmpty(), reason)
        }
    }

    private fun ensureModelLoaded(resolved: ResolvedUseCase): ResidentModel {
        check(!closed.get()) { "Runtime is closed" }
        validateRuntimeCapabilities(resolved)
        val requestedDigest = resolved.model.artifact.digest
        val current = modelResidency.reusableModelOrNull()
        if (current != null && current.handle.digest == requestedDigest && current.profileId == resolved.model.id) {
            return current
        }

        val schedulerSnapshot = scheduler.snapshot()
        check(sessions.isEmpty() && schedulerSnapshot.activeRequest == null && schedulerSnapshot.queuedRequests == 0) {
            "Cannot switch model while sessions or generation requests are active"
        }

        val stored = modelStore.find(requestedDigest)
            ?: throw BackendException("MODEL_UNAVAILABLE", "Model ${requestedDigest.sha256} is not installed")
        val verification = integrityCache.verify(modelStore, stored)
        if (!verification.valid) {
            throw BackendException("MODEL_INTEGRITY", verification.detail)
        }

        if (current != null) {
            check(unloadResidentModel()) { "Unable to unload current model before switching" }
        }
        admitModelLoad(resolved, schedulerSnapshot)

        if (!backendInitialized) {
            backend.initialize()
            backendInitialized = true
        }

        return loadResidentModel(stored, resolved)
    }

    private fun loadResidentModel(stored: StoredModel, resolved: ResolvedUseCase): ResidentModel {
        val requestedDigest = resolved.model.artifact.digest
        modelResidency.beginLoad(resolved.model.id, requestedDigest)
        return try {
            val loaded = ResidentModel(
                profileId = resolved.model.id,
                handle = backend.loadModel(stored, resolved.model),
            )
            modelResidency.loadSucceeded(loaded)
            loaded
        } catch (error: Throwable) {
            modelResidency.loadFailed()
            throw error
        }
    }

    private fun unloadResidentModel(): Boolean {
        val resident = modelResidency.beginUnload() ?: return false
        return try {
            backend.unloadModel(resident.handle)
            modelResidency.unloadSucceeded()
            true
        } catch (error: Throwable) {
            modelResidency.unloadFailed()
            throw error
        }
    }

    private fun admitModelLoad(resolved: ResolvedUseCase, schedulerSnapshot: DecodeSchedulerSnapshot) {
        val planner = memoryAwareModelLoadPlanner ?: return
        val decision = planner.plan(
            MemoryAwareModelLoadRequest(
                modelProfileId = resolved.model.id,
                residency = RuntimeResidencySnapshot(
                    modelLoaded = false,
                    residentContexts = 0,
                    activeGeneration = schedulerSnapshot.activeRequest != null,
                    queuedGenerations = schedulerSnapshot.queuedRequests,
                ),
            ),
        )
        when (decision) {
            is MemoryAwareModelLoadDecision.Allow -> runtimeTelemetry.memoryAdmission(
                resource = MemoryAdmissionResource.MODEL,
                outcome = MemoryAdmissionOutcome.ALLOW,
                estimate = decision.estimate,
            )

            is MemoryAwareModelLoadDecision.Reject -> {
                runtimeTelemetry.memoryAdmission(
                    resource = MemoryAdmissionResource.MODEL,
                    outcome = MemoryAdmissionOutcome.REJECT,
                    decisionReason = decision.reason.name,
                    admissionReason = decision.admissionReason,
                )
                if (enforceMemoryAdmission) throw ModelLoadMemoryAdmissionException(resolved.model.id, decision)
            }
        }
    }

    private fun validateRuntimeCapabilities(resolved: ResolvedUseCase) {
        val capabilities = resolved.model.runtimeCapabilities
        capabilities.requiredBackendId?.let { required ->
            check(backend.id == required) { "Runtime profile requires backend $required" }
        }
        capabilities.requiredBackendRevision?.let { required ->
            check(backend.revision == required) { "Runtime profile requires backend revision $required" }
        }
        check(!resolved.useCase.cachePolicy.enablePrefixSnapshot || capabilities.supportsPrefixSnapshot) {
            "Runtime profile does not allow prefix snapshots"
        }
        check(!resolved.useCase.cachePolicy.reuseStatelessContext || capabilities.supportsStatelessContextReuse) {
            "Runtime profile does not allow stateless context reuse"
        }
    }

    private fun BackendGenerationOutcome.metrics(): BackendGenerationMetrics = when (this) {
        is BackendGenerationOutcome.Completed -> metrics
        is BackendGenerationOutcome.Cancelled -> metrics
    }

    private fun completeGeneration(
        request: GenerationRequest,
        session: SessionDescriptor,
        lifecycle: RequestLifecycle,
        output: String,
        reasoningOutput: String,
        answerOutput: String,
        backendMetrics: BackendGenerationMetrics,
        executionStartedAt: Long,
        enqueuedAt: Long,
        firstTokenAt: AtomicLong,
        firstAnswerAt: AtomicLong,
        promptPlanningMs: Long,
        contextCreationMs: Long?,
    ) {
        val publicMetrics = backendMetrics.toPublicMetrics(
            queueMs = nanosToMillis(executionStartedAt - enqueuedAt),
            modelLoadMs = session.modelLoadDurationMs,
            modelLoadKind = session.modelLoadKind,
            timeToFirstTokenMs = firstTokenAt.get().takeIf { it != 0L }
                ?.let { nanosToMillis(it - executionStartedAt) },
            timeToFirstAnswerMs = firstAnswerAt.get().takeIf { it != 0L }
                ?.let { nanosToMillis(it - executionStartedAt) },
            totalMs = nanosToMillis(clock.nowNanos() - enqueuedAt),
            promptPlanningMs = promptPlanningMs,
            contextCreationMs = contextCreationMs,
        )
        runtimeTelemetry.completed(request.requestId, publicMetrics)
        lifecycle.finish(
            GenerationEvent.Completed(
                requestId = request.requestId,
                output = output,
                metrics = publicMetrics,
                reasoningOutput = reasoningOutput,
                answerOutput = answerOutput,
            ),
        )
    }

    private fun releaseSessionRequest(session: SessionDescriptor) {
        if (session.lifecycle.releaseRequest()) {
            releaseSession(session)
        }
    }

    private fun releaseSession(session: SessionDescriptor) {
        if (!session.lifecycle.tryBeginRelease()) {
            return
        }
        val release = runCatching {
            synchronized(resourceLock) {
                session.context?.let(backend::releaseContext)
                session.context = null
                sessions.remove(session.id, session)
            }
        }
        if (release.isFailure) {
            session.lifecycle.releaseFailed()
            state.set(RuntimeState.DEGRADED)
            release.getOrThrow()
        }
        session.lifecycle.releaseSucceeded()
        attemptDeferredModelUnload(ignoreActiveGeneration = false)
    }

    private fun releaseForCriticalMemory(action: RuntimeMemoryAction): RuntimeMemoryResult {
        deferredModelUnload.set(true)
        sessions.values.forEach { session -> session.lifecycle.beginClose() }
        val cancelled = scheduler.cancelAll()
        sessions.values.forEach { session ->
            if (session.lifecycle.isReleaseReady()) {
                releaseSession(session)
            }
        }
        val unloaded = attemptDeferredModelUnload(ignoreActiveGeneration = false)
        if (!unloaded) {
            state.set(RuntimeState.DEGRADED)
        }
        return RuntimeMemoryResult(
            action = action,
            cancelledRequests = cancelled,
            modelUnloaded = unloaded,
            deferred = !unloaded,
        )
    }

    private fun attemptDeferredModelUnload(ignoreActiveGeneration: Boolean): Boolean {
        if (!deferredModelUnload.get() && !closed.get()) return false
        return synchronized(resourceLock) {
            val schedulerSnapshot = scheduler.snapshot()
            val activeBlocksUnload = !ignoreActiveGeneration && schedulerSnapshot.activeRequest != null
            if (sessions.isNotEmpty() || schedulerSnapshot.queuedRequests != 0 || activeBlocksUnload) {
                return@synchronized false
            }

            unloadResidentModel()
            if (closed.get() && backendInitialized) {
                backend.shutdown()
                backendInitialized = false
            }
            deferredModelUnload.set(false)
            state.set(RuntimeState.IDLE)
            true
        }
    }

    private fun failImmediately(requestId: RequestId, listener: GenerationListener, error: LocalLlmError): GenerationHandle {
        runtimeTelemetry.rejected(requestId, error)
        runCatching { listener.onEvent(GenerationEvent.Failed(requestId, error)) }
        return NoOpGenerationHandle(requestId)
    }

    private fun BackendGenerationMetrics.toPublicMetrics(
        queueMs: Long,
        modelLoadMs: Long?,
        modelLoadKind: ModelLoadKind,
        timeToFirstTokenMs: Long?,
        timeToFirstAnswerMs: Long?,
        totalMs: Long,
        promptPlanningMs: Long,
        contextCreationMs: Long?,
    ): GenerationMetrics = GenerationMetrics(
        queueMs = queueMs,
        modelLoadMs = modelLoadMs,
        timeToFirstTokenMs = timeToFirstTokenMs,
        totalMs = totalMs,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        decodeTokensPerSecond = if (generationDurationMs > 0) {
            outputTokens * 1_000.0 / generationDurationMs
        } else {
            null
        },
        prefillMs = promptDurationMs,
        decodeMs = generationDurationMs,
        modelLoadKind = modelLoadKind,
        stopReason = stopReason,
        promptPlanningMs = promptPlanningMs,
        contextCreationMs = contextCreationMs,
        timeToFirstAnswerMs = timeToFirstAnswerMs,
        reasoningTokens = reasoningTokens,
        answerTokens = answerTokens,
    )

    private fun nanosToMillis(nanos: Long): Long = nanos / 1_000_000

    private data class BatchSessionBinding(val request: GenerationRequest, val session: SessionDescriptor)

    private data class PreparedGeneration(
        val resolved: ResolvedRequestConfiguration,
        val promptPlan: BackendPromptPlan,
        val contextResult: ContextMaterialization,
        val capabilities: BackendModelCapabilities,
        val executionStartedAt: Long,
        val promptPlanningMs: Long,
    )

    private data class StreamedGeneration(
        val outcome: BackendGenerationOutcome,
        val guardReason: StopReason?,
        val output: String,
        val reasoningOutput: String,
        val answerOutput: String,
        val firstTokenAt: AtomicLong,
        val firstAnswerAt: AtomicLong,
    )

    private data class PreparedEvaluationBatchCase(
        val binding: BatchSessionBinding,
        val resolved: ResolvedRequestConfiguration,
        val promptPlan: BackendPromptPlan,
        val contextPlan: ContextPlanningResult,
        val promptPlanningMs: Long,
        val backendRequest: BackendGenerationRequest,
    )

    private data class SessionDescriptor(
        val id: SessionId,
        val applicationId: ApplicationId,
        val useCaseId: UseCaseId,
        val resolved: ResolvedUseCase,
        val model: BackendModelHandle,
        val options: SessionOptions,
        val modelLoadDurationMs: Long?,
        val modelLoadKind: ModelLoadKind,
        @Volatile var context: BackendContextHandle? = null,
        val lifecycle: SessionLifecycle = SessionLifecycle(),
    )

    private data class ContextMaterialization(val context: BackendContextHandle, val creationMs: Long?, val created: Boolean)
}

private class GenerationCancelledException : RuntimeException()

private class RequestLifecycle(val requestId: RequestId, private val listener: GenerationListener, private val onTerminal: () -> Unit) {
    private val generationLifecycle = GenerationLifecycle()
    private val schedulerFinished = AtomicBoolean(false)
    private val terminalEvent = AtomicReference<GenerationEvent?>(null)
    private val terminalDelivered = AtomicBoolean(false)

    fun requestCancellation(): Boolean = generationLifecycle.requestCancellation()

    fun isCancellationRequested(): Boolean = generationLifecycle.isCancellationRequested()

    fun emit(event: GenerationEvent) {
        if (!generationLifecycle.isTerminal()) {
            runCatching { listener.onEvent(event) }
        }
    }

    fun ensureNotCancelled() {
        if (generationLifecycle.isCancellationRequested()) throw GenerationCancelledException()
    }

    fun finish(event: GenerationEvent) {
        if (!generationLifecycle.tryFinish()) {
            return
        }
        val release = runCatching { onTerminal() }
        terminalEvent.set(event)
        deliverTerminalIfReady()
        release.getOrThrow()
    }

    fun schedulerFinished() {
        schedulerFinished.set(true)
        deliverTerminalIfReady()
    }

    private fun deliverTerminalIfReady() {
        if (!schedulerFinished.get()) return
        val event = terminalEvent.get() ?: return
        if (terminalDelivered.compareAndSet(false, true)) {
            runCatching { listener.onEvent(event) }
        }
    }
}

private class RuntimeGenerationHandle(
    override val requestId: RequestId,
    private val schedulerHandle: DecodeTaskHandle,
    private val lifecycle: RequestLifecycle,
) : GenerationHandle {
    override fun cancel() {
        if (lifecycle.requestCancellation()) {
            schedulerHandle.cancel()
        }
    }
}

private class NoOpGenerationHandle(override val requestId: RequestId) : GenerationHandle {
    override fun cancel() = Unit
}
