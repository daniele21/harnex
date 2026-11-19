package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConfigurationErrorCode
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.RuntimeState
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.AppModelBinding
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GenerationDefaults
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.ModelProfileRegistry
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.models.UseCaseCachePolicy
import io.github.daniele21.localllm.models.UseCaseProfile
import io.github.daniele21.localllm.store.ModelStore
import io.github.daniele21.localllm.store.ModelStoreSnapshot
import io.github.daniele21.localllm.store.StoredModel
import io.github.daniele21.localllm.store.VerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeEvaluationBatchTest {
    @Test
    fun `batch uses dedicated context and preserves exact request order`() {
        val fixture = BatchRuntimeFixture()
        val sessions = fixture.sessions(2)
        val terminal = CountDownLatch(1)
        var outcome: RuntimeEvaluationBatchOutcome? = null

        fixture.runtime.generateEvaluationBatch(
            fixture.batchRequest("batch", sessions),
            RuntimeEvaluationBatchListener {
                outcome = it
                terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val completed = outcome as RuntimeEvaluationBatchOutcome.Completed
        assertEquals(listOf("case-0", "case-1"), completed.cases.map { it.requestId.value })
        assertEquals(1, fixture.backend.batchContextCreateCalls)
        assertEquals(1, fixture.backend.batchGenerationCalls)
        assertEquals(1, fixture.backend.batchContextReleaseCalls)
        assertEquals(0, fixture.backend.ordinaryContextCreateCalls)
        assertEquals(0, fixture.backend.ordinaryGenerationCalls)
        assertEquals(2, fixture.backend.lastBatchConfiguration?.maxSequences)
        assertTrue(fixture.backend.lastBatchConfiguration!!.perSequenceContextSize > 0)
        fixture.close()
    }

    @Test
    fun `batch preserves per-case sampling overrides with no reasoning control`() {
        val fixture = BatchRuntimeFixture()
        val initial = fixture.batchRequest("batch-settings", fixture.sessions(2))
        val requests = initial.requests.mapIndexed { index, request ->
            request.copy(
                overrides = GenerationOverrides(
                    maxOutputTokens = 20 + index,
                    temperature = 0.3f + index * 0.2f,
                    topP = 0.8f,
                    topK = 12,
                    minP = 0.07f,
                    presencePenalty = 0.2f,
                    repeatPenalty = 1.1f,
                    repeatLastN = 32,
                    seed = 17L + index,
                ),
            )
        }
        val terminal = CountDownLatch(1)
        var outcome: RuntimeEvaluationBatchOutcome? = null

        fixture.runtime.generateEvaluationBatch(
            initial.copy(requests = requests),
            RuntimeEvaluationBatchListener {
                outcome = it
                terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(outcome is RuntimeEvaluationBatchOutcome.Completed)
        val projected = fixture.backend.lastBatchRequests
        assertEquals(requests.map { it.requestId.value }, projected.map { it.requestId })
        assertEquals(listOf(20, 21), projected.map { it.maxOutputTokens })
        assertEquals(listOf(17L, 18L), projected.map { it.seed })
        assertEquals(listOf(0.07f, 0.07f), projected.map { it.minP })
        assertEquals(listOf(0.2f, 0.2f), projected.map { it.presencePenalty })
        assertTrue(projected.all { it.reasoningControl == null })
        fixture.close()
    }

    @Test
    fun `batch is one scheduler operation behind an active ordinary generation`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.blockOrdinaryGeneration = CountDownLatch(1)
        val ordinarySession = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val ordinaryTerminal = CountDownLatch(1)
        fixture.runtime.generate(
            fixture.request("ordinary", ordinarySession),
        ) { event ->
            if (event is io.github.daniele21.localllm.contracts.GenerationEvent.Completed ||
                event is io.github.daniele21.localllm.contracts.GenerationEvent.Failed
            ) {
                ordinaryTerminal.countDown()
            }
        }
        assertTrue(fixture.backend.ordinaryGenerationStarted.await(2, TimeUnit.SECONDS))

        val batchTerminal = CountDownLatch(1)
        fixture.runtime.generateEvaluationBatch(
            fixture.batchRequest("queued-batch", fixture.sessions(2)),
            RuntimeEvaluationBatchListener { batchTerminal.countDown() },
        )

        assertFalse(fixture.backend.batchGenerationStarted.await(150, TimeUnit.MILLISECONDS))
        fixture.backend.blockOrdinaryGeneration!!.countDown()
        assertTrue(ordinaryTerminal.await(2, TimeUnit.SECONDS))
        assertTrue(fixture.backend.batchGenerationStarted.await(2, TimeUnit.SECONDS))
        assertTrue(batchTerminal.await(2, TimeUnit.SECONDS))
        fixture.close()
    }

    @Test
    fun `running case cancellation is delegated without cancelling sibling case`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.blockBatchGeneration = CountDownLatch(1)
        val sessions = fixture.sessions(2)
        val terminal = CountDownLatch(1)
        var outcome: RuntimeEvaluationBatchOutcome? = null

        val handle = fixture.runtime.generateEvaluationBatch(
            fixture.batchRequest("batch-cancel-case", sessions),
            RuntimeEvaluationBatchListener {
                outcome = it
                terminal.countDown()
            },
        )
        assertTrue(fixture.backend.batchGenerationStarted.await(2, TimeUnit.SECONDS))
        assertTrue(handle.cancelCase(RequestId("case-0")))
        fixture.backend.blockBatchGeneration!!.countDown()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val completed = outcome as RuntimeEvaluationBatchOutcome.Completed
        assertTrue(completed.cases[0] is RuntimeEvaluationBatchCaseResult.Cancelled)
        assertTrue(completed.cases[1] is RuntimeEvaluationBatchCaseResult.Completed)
        assertEquals(listOf("case-0"), fixture.backend.cancelledCaseIds.toList())
        fixture.close()
    }

    @Test
    fun `batch invalid generation settings remain a typed configuration failure before context creation`() {
        val fixture = BatchRuntimeFixture()
        try {
            val request = fixture.batchRequest("invalid-batch", fixture.sessions(2)).let { batch ->
                batch.copy(requests = batch.requests.map { it.copy(overrides = GenerationOverrides(maxOutputTokens = 0)) })
            }
            val terminal = CountDownLatch(1)
            var outcomes = 0
            var outcome: RuntimeEvaluationBatchOutcome? = null
            fixture.runtime.generateEvaluationBatch(
                request,
                RuntimeEvaluationBatchListener {
                    outcomes++
                    outcome = it
                    terminal.countDown()
                },
            )

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            val failure = (outcome as RuntimeEvaluationBatchOutcome.Failed).error as LocalLlmError.Configuration
            assertEquals(ConfigurationErrorCode.INVALID_GENERATION_CONFIGURATION, failure.reason)
            assertEquals(0, fixture.backend.batchContextCreateCalls)
            assertEquals(0, fixture.backend.batchContextReleaseCalls)
            assertEquals(1, outcomes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `batch backend failure retains typed code and releases the native context once`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.batchFailure = BackendException("OUTPUT_CONSTRAINT_UNSUPPORTED", "Output schema unsupported")
        try {
            val terminal = CountDownLatch(1)
            var outcomes = 0
            var outcome: RuntimeEvaluationBatchOutcome? = null
            fixture.runtime.generateEvaluationBatch(
                fixture.batchRequest("backend-failure", fixture.sessions(2)),
                RuntimeEvaluationBatchListener {
                    outcomes++
                    outcome = it
                    terminal.countDown()
                },
            )

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            val failure = (outcome as RuntimeEvaluationBatchOutcome.Failed).error as LocalLlmError.Configuration
            assertEquals(ConfigurationErrorCode.OUTPUT_CONSTRAINT_UNSUPPORTED, failure.reason)
            assertEquals(1, fixture.backend.batchContextCreateCalls)
            assertEquals(1, fixture.backend.batchContextReleaseCalls)
            assertEquals(1, outcomes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `batch unexpected failure retains native error and releases its context once`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.batchFailure = IllegalStateException("Batch decode failure")
        try {
            val terminal = CountDownLatch(1)
            var outcomes = 0
            var outcome: RuntimeEvaluationBatchOutcome? = null
            fixture.runtime.generateEvaluationBatch(
                fixture.batchRequest("unexpected-failure", fixture.sessions(2)),
                RuntimeEvaluationBatchListener {
                    outcomes++
                    outcome = it
                    terminal.countDown()
                },
            )

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            val failure = (outcome as RuntimeEvaluationBatchOutcome.Failed).error as LocalLlmError.NativeRuntime
            assertEquals("Batch decode failure", failure.message)
            assertEquals(1, fixture.backend.batchContextCreateCalls)
            assertEquals(1, fixture.backend.batchContextReleaseCalls)
            assertEquals(1, outcomes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelled running batch releases context once and reports one terminal failure`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.blockBatchGeneration = CountDownLatch(1)
        try {
            val terminal = CountDownLatch(1)
            var outcomes = 0
            var outcome: RuntimeEvaluationBatchOutcome? = null
            val handle = fixture.runtime.generateEvaluationBatch(
                fixture.batchRequest("cancelled-batch", fixture.sessions(2)),
                RuntimeEvaluationBatchListener {
                    outcomes++
                    outcome = it
                    terminal.countDown()
                },
            )
            assertTrue(fixture.backend.batchGenerationStarted.await(2, TimeUnit.SECONDS))
            assertTrue(handle.cancel())
            fixture.backend.blockBatchGeneration!!.countDown()

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            assertTrue((outcome as RuntimeEvaluationBatchOutcome.Failed).error is LocalLlmError.Cancelled)
            assertEquals(1, fixture.backend.batchContextCreateCalls)
            assertEquals(1, fixture.backend.batchContextReleaseCalls)
            assertEquals(1, outcomes)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `release failure after successful batch preserves degraded runtime state`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.batchReleaseFailure = IllegalStateException("Native context release failed")
        try {
            val terminal = CountDownLatch(1)
            var outcome: RuntimeEvaluationBatchOutcome? = null
            fixture.runtime.generateEvaluationBatch(
                fixture.batchRequest("release-failure", fixture.sessions(2)),
                RuntimeEvaluationBatchListener {
                    outcome = it
                    terminal.countDown()
                },
            )

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            fixture.awaitWorkerIdle()
            val failure = (outcome as RuntimeEvaluationBatchOutcome.Failed).error as LocalLlmError.NativeRuntime
            assertEquals("Native context release failed", failure.message)
            assertEquals(1, fixture.backend.batchContextReleaseCalls)
            assertEquals(RuntimeState.DEGRADED, fixture.runtime.runtimeSnapshot().state)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `release failure while handling backend error preserves original error and degraded state`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.batchFailure = BackendException("OUTPUT_CONSTRAINT_UNSUPPORTED", "Output schema unsupported")
        fixture.backend.batchReleaseFailure = IllegalStateException("Native context release failed")
        try {
            val terminal = CountDownLatch(1)
            var outcome: RuntimeEvaluationBatchOutcome? = null
            fixture.runtime.generateEvaluationBatch(
                fixture.batchRequest("backend-and-release-failure", fixture.sessions(2)),
                RuntimeEvaluationBatchListener {
                    outcome = it
                    terminal.countDown()
                },
            )

            assertTrue(terminal.await(2, TimeUnit.SECONDS))
            fixture.awaitWorkerIdle()
            val failure = (outcome as RuntimeEvaluationBatchOutcome.Failed).error as LocalLlmError.Configuration
            assertEquals(ConfigurationErrorCode.OUTPUT_CONSTRAINT_UNSUPPORTED, failure.reason)
            assertEquals(1, fixture.backend.batchContextReleaseCalls)
            assertEquals(RuntimeState.DEGRADED, fixture.runtime.runtimeSnapshot().state)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `queued per-case cancellation fails closed instead of changing batch membership`() {
        val fixture = BatchRuntimeFixture()
        fixture.backend.blockOrdinaryGeneration = CountDownLatch(1)
        val ordinarySession = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.runtime.generate(fixture.request("ordinary-blocker", ordinarySession)) { }
        assertTrue(fixture.backend.ordinaryGenerationStarted.await(2, TimeUnit.SECONDS))

        val handle = fixture.runtime.generateEvaluationBatch(
            fixture.batchRequest("queued-case-cancel", fixture.sessions(2)),
            RuntimeEvaluationBatchListener { },
        )

        assertFalse(handle.cancelCase(RequestId("case-0")))
        fixture.backend.blockOrdinaryGeneration!!.countDown()
        fixture.close()
    }
}

private class BatchRuntimeFixture {
    val applicationId = ApplicationId("evaluation-app")
    val useCaseId = UseCaseId("evaluation-use-case")
    private val digest = ModelDigest("c".repeat(64))
    private val modelFile = File.createTempFile("batch-runtime-model", ".gguf").apply {
        writeText("model")
        deleteOnExit()
    }
    val backend = BatchFakeInferenceBackend()
    private val resolved = resolvedUseCase()
    private val registry = object : ModelProfileRegistry {
        override fun resolve(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase = resolved
    }
    private val store = object : ModelStore {
        override fun find(digest: ModelDigest): StoredModel? = if (digest == this@BatchRuntimeFixture.digest) {
            StoredModel(digest, modelFile, modelFile.length(), verified = true)
        } else {
            null
        }

        override fun import(source: File, artifact: GgufArtifact): StoredModel = error("Not used")

        override fun verify(digest: ModelDigest): VerificationResult = VerificationResult(true, digest, "valid")

        override fun remove(digest: ModelDigest): Boolean = false

        override fun snapshot(): ModelStoreSnapshot = ModelStoreSnapshot(0, 0, emptyList())
    }
    val runtime = RuntimeOrchestrator(registry, store, backend)

    fun sessions(count: Int): List<SessionId> = List(count) { runtime.createSession(applicationId, useCaseId) }

    fun batchRequest(id: String, sessions: List<SessionId>): RuntimeEvaluationBatchRequest = RuntimeEvaluationBatchRequest(
        batchId = RequestId(id),
        requests = sessions.mapIndexed { index, sessionId -> request("case-$index", sessionId) },
    )

    fun request(id: String, sessionId: SessionId): GenerationRequest = GenerationRequest(
        requestId = RequestId(id),
        sessionId = sessionId,
        applicationId = applicationId,
        useCaseId = useCaseId,
        input = "prompt $id",
    )

    fun awaitWorkerIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (runtime.memoryResourceSnapshot().activeGeneration && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse(runtime.memoryResourceSnapshot().activeGeneration)
    }

    fun close() {
        backend.blockOrdinaryGeneration?.countDown()
        backend.blockBatchGeneration?.countDown()
        runtime.close()
        modelFile.delete()
    }

    private fun resolvedUseCase(): ResolvedUseCase {
        val profile = GgufModelProfile(
            id = "batch-profile",
            artifact = GgufArtifact(
                digest = digest,
                fileName = "batch.gguf",
                sizeBytes = modelFile.length(),
                architecture = "qwen2",
                quantization = "Q4_K_M",
                source = ArtifactSource.Imported("batch-test"),
            ),
            contextSize = 2_048,
            batchSize = 128,
            microBatchSize = 64,
            cpuThreads = 2,
            batchThreads = 2,
            gpuLayers = 0,
        )
        val useCase = UseCaseProfile(
            id = "batch-use-case-profile",
            modelProfileId = profile.id,
            systemPromptVersion = "v1",
            generationDefaults = GenerationDefaults(
                maxOutputTokens = 16,
                temperature = 0f,
                topP = 1f,
                topK = 0,
                seed = 42,
            ),
            outputMode = OutputMode.TEXT,
            cachePolicy = UseCaseCachePolicy(0, false, false, false),
            healthSuiteId = "health",
        )
        return ResolvedUseCase(
            binding = AppModelBinding(applicationId, useCaseId, useCase.id),
            useCase = useCase,
            model = profile,
        )
    }
}

private data class BatchFakeModelHandle(
    override val digest: ModelDigest,
    override val profileId: String,
    override val loadDurationMs: Long = 1,
) : BackendModelHandle

private data class BatchFakeContextHandle(override val model: BackendModelHandle, override val contextSize: Int) : BackendContextHandle

private data class BatchFakeEvaluationContextHandle(
    override val model: BackendModelHandle,
    override val contextSize: Int,
    override val perSequenceContextSize: Int,
    override val maxSequences: Int,
) : BackendEvaluationBatchContextHandle

private class BatchFakeInferenceBackend :
    InferenceBackend,
    EvaluationBatchInferenceBackend {
    override val id: String = "batch-fake"
    override val revision: String = "test-v1"
    var ordinaryContextCreateCalls = 0
    var ordinaryGenerationCalls = 0
    var batchContextCreateCalls = 0
    var batchGenerationCalls = 0
    var batchContextReleaseCalls = 0
    var lastBatchConfiguration: BackendEvaluationBatchContextConfiguration? = null
    @Volatile var lastBatchRequests: List<BackendGenerationRequest> = emptyList()
    var blockOrdinaryGeneration: CountDownLatch? = null
    var blockBatchGeneration: CountDownLatch? = null
    var batchFailure: Throwable? = null
    var batchReleaseFailure: Throwable? = null
    val ordinaryGenerationStarted = CountDownLatch(1)
    val batchGenerationStarted = CountDownLatch(1)
    val cancelledCaseIds = Collections.synchronizedSet(LinkedHashSet<String>())
    private val loadedModels = ConcurrentHashMap<String, BatchFakeModelHandle>()

    override fun initialize() = Unit

    override fun shutdown() = Unit

    override fun loadModel(source: BackendModelSource, profile: GgufModelProfile): BackendModelHandle =
        BatchFakeModelHandle(source.digest, profile.id).also { loadedModels[profile.id] = it }

    override fun unloadModel(model: BackendModelHandle) {
        loadedModels.remove(model.profileId)
    }

    override fun modelCapabilities(model: BackendModelHandle): BackendModelCapabilities = BackendModelCapabilities(
        maximumContextTokens = 4_096,
        supportsGrammar = true,
        supportsReasoningTransition = true,
    )

    override fun planPrompt(model: BackendModelHandle, request: BackendPromptPlanningRequest): BackendPromptPlan = fakePromptPlan(request)

    override fun createContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendContextConfiguration,
    ): BackendContextHandle {
        ordinaryContextCreateCalls += 1
        return BatchFakeContextHandle(model, configuration.contextSize)
    }

    override fun releaseContext(context: BackendContextHandle) = Unit

    override fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome {
        ordinaryGenerationCalls += 1
        ordinaryGenerationStarted.countDown()
        blockOrdinaryGeneration?.await()
        onChunk("ordinary", 1)
        return completedMetrics()
    }

    override fun cancel(requestId: String): Boolean {
        blockOrdinaryGeneration?.countDown()
        return true
    }

    override fun createEvaluationBatchContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendEvaluationBatchContextConfiguration,
    ): BackendEvaluationBatchContextHandle {
        batchContextCreateCalls += 1
        lastBatchConfiguration = configuration
        return BatchFakeEvaluationContextHandle(
            model = model,
            contextSize = configuration.perSequenceContextSize * configuration.maxSequences,
            perSequenceContextSize = configuration.perSequenceContextSize,
            maxSequences = configuration.maxSequences,
        )
    }

    override fun releaseEvaluationBatchContext(context: BackendEvaluationBatchContextHandle) {
        batchContextReleaseCalls += 1
        batchReleaseFailure?.let { throw it }
    }

    override fun generateEvaluationBatch(
        context: BackendEvaluationBatchContextHandle,
        requests: List<BackendGenerationRequest>,
    ): BackendEvaluationBatchResult {
        lastBatchRequests = requests
        batchGenerationCalls += 1
        batchGenerationStarted.countDown()
        blockBatchGeneration?.await()
        batchFailure?.let { throw it }
        return BackendEvaluationBatchResult(
            requests.map { request ->
                if (request.requestId in cancelledCaseIds) {
                    BackendEvaluationBatchCaseResult(
                        requestId = request.requestId,
                        output = "",
                        outcome = BackendGenerationOutcome.Cancelled(metrics()),
                    )
                } else {
                    BackendEvaluationBatchCaseResult(
                        requestId = request.requestId,
                        output = "batch-${request.requestId}",
                        outcome = completedMetrics(),
                    )
                }
            },
        )
    }

    override fun cancelEvaluationCase(requestId: String): Boolean {
        cancelledCaseIds += requestId
        return true
    }

    private fun completedMetrics(): BackendGenerationOutcome.Completed = BackendGenerationOutcome.Completed(metrics())

    private fun metrics(): BackendGenerationMetrics = BackendGenerationMetrics(
        inputTokens = 4,
        outputTokens = 1,
        promptDurationMs = 2,
        generationDurationMs = 3,
    )
}
