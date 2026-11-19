package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConfigurationErrorCode
import io.github.daniele21.localllm.contracts.ContextPolicy
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SeedPolicy
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.StopReason
import io.github.daniele21.localllm.contracts.ThinkingMode
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.AppModelBinding
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GenerationDefaults
import io.github.daniele21.localllm.models.GenerationGuardPolicy
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeOrchestratorTest {
    @Test
    fun `prepare verifies once and reuses loaded model`() {
        val fixture = RuntimeFixture()

        val first = fixture.runtime.prepare(fixture.applicationId, fixture.useCaseId)
        val second = fixture.runtime.prepare(fixture.applicationId, fixture.useCaseId)

        assertTrue(first.ready)
        assertTrue(second.ready)
        assertEquals(fixture.digest, first.modelDigest)
        assertEquals(1, fixture.store.verificationCalls)
        assertEquals(1, fixture.backend.initializeCalls)
        assertEquals(1, fixture.backend.loadCalls)
        fixture.close()
    }

    @Test
    fun `generation emits ordered streaming events and complete metrics`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("generation", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("Queued", "Prepared", "Started", "TextDelta", "TextDelta", "Completed"),
            events.map { it::class.simpleName },
        )
        val completed = events.last() as GenerationEvent.Completed
        assertEquals("hello world", completed.output)
        assertEquals(2, completed.metrics.inputTokens)
        assertEquals(2, completed.metrics.outputTokens)
        assertEquals(200.0, completed.metrics.decodeTokensPerSecond!!, 0.001)
        fixture.close()
    }

    @Test
    fun `backend failure after preparation emits one typed terminal failure`() {
        val fixture = RuntimeFixture()
        fixture.backend.generateFailureCode = "GENERATION_FAILED"
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("backend-failure", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("Queued", "Prepared", "Started", "Failed"), events.map { it::class.simpleName })
        assertEquals(1, events.count { it is GenerationEvent.Completed || it is GenerationEvent.Failed })
        assertTrue((events.last() as GenerationEvent.Failed).error is LocalLlmError.NativeRuntime)
        fixture.close()
    }

    @Test
    fun `auto context uses the smallest approved capacity after exact prompt planning`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("auto-context", session),
            GenerationListener { if (it is GenerationEvent.Completed || it is GenerationEvent.Failed) terminal.countDown() },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1_024), fixture.backend.createdContextSizes)
        fixture.close()
    }

    @Test
    fun `insufficient manual context fails without creating native context`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(
            fixture.applicationId,
            fixture.useCaseId,
            SessionOptions(contextPolicy = ContextPolicy.Manual(256)),
        )
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("manual-context", session),
            GenerationListener {
                events += it
                if (it is GenerationEvent.Failed) terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(events.last() is GenerationEvent.Failed)
        assertTrue(fixture.backend.createdContextSizes.isEmpty())
        fixture.close()
    }

    @Test
    fun `request sampling overrides reach backend and prepared metadata`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)
        val request = fixture.request("overrides", session).copy(
            overrides = GenerationOverrides(
                maxOutputTokens = 24,
                temperature = 0.7f,
                topP = 0.8f,
                topK = 25,
                repeatPenalty = 1.1f,
                repeatLastN = 96,
                seedPolicy = SeedPolicy.Fixed(99),
            ),
        )

        fixture.runtime.generate(
            request,
            GenerationListener {
                events += it
                if (it is GenerationEvent.Completed || it is GenerationEvent.Failed) terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val prepared = events.filterIsInstance<GenerationEvent.Prepared>().single().configuration
        assertEquals(0.7f, prepared.temperature)
        assertEquals(0.8f, prepared.topP)
        assertEquals(25, prepared.topK)
        assertEquals(1.1f, prepared.repeatPenalty)
        assertEquals(96, prepared.repeatLastN)
        assertEquals(99L, prepared.effectiveSeed)
        assertEquals(24, fixture.backend.lastGenerationRequest?.maxOutputTokens)
        assertEquals(99L, fixture.backend.lastGenerationRequest?.seed)
        assertEquals(1.1f, fixture.backend.lastGenerationRequest?.repeatPenalty)
        assertEquals(96, fixture.backend.lastGenerationRequest?.repeatLastN)
        fixture.close()
    }

    @Test
    fun `prompt stop policy reaches backend generation`() {
        val fixture = RuntimeFixture()
        fixture.backend.plannedStopSequences = listOf("<stop>")
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("stops", session),
            GenerationListener { if (it is GenerationEvent.Completed || it is GenerationEvent.Failed) terminal.countDown() },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("<stop>"), fixture.backend.lastGenerationRequest?.stopSequences)
        fixture.close()
    }

    @Test
    fun `cancellation after prompt planning avoids context creation and prefill`() {
        val fixture = RuntimeFixture()
        fixture.backend.blockPlanning = CountDownLatch(1)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())

        val handle = fixture.runtime.generate(
            fixture.request("cancel-planning", session),
            GenerationListener {
                events += it
                if (it is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(fixture.backend.planningStarted.await(2, TimeUnit.SECONDS))
        handle.cancel()
        fixture.backend.blockPlanning?.countDown()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue((events.last() as GenerationEvent.Failed).error is LocalLlmError.Cancelled)
        assertTrue(fixture.backend.createdContextSizes.isEmpty())
        assertEquals(0, fixture.backend.generationCalls)
        fixture.close()
    }

    @Test
    fun `cancellation during context creation releases new context before prefill`() {
        val fixture = RuntimeFixture()
        fixture.backend.blockContextCreation = CountDownLatch(1)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)

        val handle = fixture.runtime.generate(
            fixture.request("cancel-context", session),
            GenerationListener { if (it is GenerationEvent.Failed) terminal.countDown() },
        )
        assertTrue(fixture.backend.contextCreationStarted.await(2, TimeUnit.SECONDS))
        handle.cancel()
        fixture.backend.blockContextCreation?.countDown()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.backend.releaseCalls)
        assertEquals(0, fixture.backend.generationCalls)
        fixture.close()
    }

    @Test
    fun `invalid output constraint is exposed as typed configuration failure`() {
        val fixture = RuntimeFixture()
        fixture.backend.generateFailureCode = "INVALID_OUTPUT_CONSTRAINT"
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())

        fixture.runtime.generate(
            fixture.request("invalid-schema", session),
            GenerationListener {
                events += it
                if (it is GenerationEvent.Failed) terminal.countDown()
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        val error = (events.last() as GenerationEvent.Failed).error as LocalLlmError.Configuration
        assertEquals(ConfigurationErrorCode.INVALID_OUTPUT_CONSTRAINT, error.reason)
        fixture.close()
    }

    @Test
    fun `queued cancellation never starts backend generation`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.backend.blockGeneration = CountDownLatch(1)
        fixture.runtime.generate(
            fixture.request("active", session),
            GenerationListener {},
        )
        assertTrue(fixture.backend.generationStarted.await(2, TimeUnit.SECONDS))

        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)
        val queued = fixture.runtime.generate(
            fixture.request("queued", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        queued.cancel()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(events.first() is GenerationEvent.Queued)
        assertTrue((events.last() as GenerationEvent.Failed).error is LocalLlmError.Cancelled)
        assertFalse(events.any { it is GenerationEvent.Started })
        fixture.backend.blockGeneration?.countDown()
        fixture.close()
    }

    @Test
    fun `running cancellation delegates to backend and returns cancelled terminal`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.backend.blockGeneration = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())

        val handle = fixture.runtime.generate(
            fixture.request("running", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(fixture.backend.generationStarted.await(2, TimeUnit.SECONDS))
        handle.cancel()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.backend.cancelCalls)
        assertTrue((events.last() as GenerationEvent.Failed).error is LocalLlmError.Cancelled)
        fixture.close()
    }

    @Test
    fun `running cancellation dominates backend failure released by cancel`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.backend.blockGeneration = CountDownLatch(1)
        fixture.backend.generateFailureAfterUnblockCode = "GENERATION_FAILED"
        val terminal = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())

        val handle = fixture.runtime.generate(
            fixture.request("running-failure-race", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(fixture.backend.generationStarted.await(2, TimeUnit.SECONDS))
        handle.cancel()

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(1, fixture.backend.cancelCalls)
        assertTrue((events.last() as GenerationEvent.Failed).error is LocalLlmError.Cancelled)
        fixture.close()
    }

    @Test
    fun `generation guard completes with typed reason instead of cancellation`() {
        val fixture = RuntimeFixture(
            generationDefaults = GenerationDefaults(
                maxOutputTokens = 16,
                temperature = 0f,
                topP = 1f,
                topK = 0,
                seed = 42,
                thinkingMode = ThinkingMode.ENABLED,
                guardPolicy = GenerationGuardPolicy(
                    enabled = true,
                    thinkingTokenBudget = 1,
                    repetitionActivationTokens = 8,
                    observationWindowChars = 512,
                    minPatternChars = 24,
                    maxPatternChars = 64,
                    repetitionOccurrences = 4,
                ),
            ),
        )
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val events = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        val terminal = CountDownLatch(1)
        fixture.runtime.generate(
            fixture.request("guard", session),
            GenerationListener { event ->
                events += event
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        // The fake backend reports Cancelled when the guard stops streaming; the public result must still complete once.
        assertEquals(1, events.count { it is GenerationEvent.Completed || it is GenerationEvent.Failed })
        val completed = events.last() as GenerationEvent.Completed
        assertEquals(StopReason.GENERATION_GUARD_THINKING_BUDGET, completed.metrics.stopReason)
        assertEquals("hello ", completed.output)
        fixture.close()
    }

    @Test
    fun `closing session defers context release until active request terminates`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.backend.blockGeneration = CountDownLatch(1)
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("deferred-close", session),
            GenerationListener { event ->
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(fixture.backend.generationStarted.await(2, TimeUnit.SECONDS))
        fixture.runtime.closeSession(session)
        assertEquals(0, fixture.backend.releaseCalls)

        fixture.backend.blockGeneration?.countDown()
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(eventually { fixture.backend.releaseCalls == 1 })
        fixture.close()
    }

    @Test
    fun `model cannot switch while session is active`() {
        val fixture = RuntimeFixture()
        fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)

        val result = fixture.runtime.prepare(fixture.applicationId, fixture.secondUseCaseId)

        assertFalse(result.ready)
        assertTrue(result.detail.contains("Cannot switch model"))
        assertEquals(1, fixture.backend.loadCalls)
        fixture.close()
    }

    @Test
    fun `idle model can be unloaded after session close`() {
        val fixture = RuntimeFixture()
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        fixture.runtime.closeSession(session)

        assertTrue(eventually { fixture.runtime.runtimeSnapshot().activeSessions == 0 })
        assertTrue(fixture.runtime.unloadIdleModel())
        assertEquals(1, fixture.backend.unloadCalls)
        assertNull(fixture.runtime.runtimeSnapshot().loadedModel)
        fixture.close()
    }

    private fun eventually(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

private class RuntimeFixture(private val generationDefaults: GenerationDefaults = defaultGenerationDefaults()) {
    val applicationId = ApplicationId("app")
    val useCaseId = UseCaseId("primary")
    val secondUseCaseId = UseCaseId("secondary")
    val digest = ModelDigest("a".repeat(64))
    private val secondDigest = ModelDigest("b".repeat(64))
    private val modelFile = File.createTempFile("runtime-model", ".gguf").apply {
        writeText("model")
        deleteOnExit()
    }
    val store = FakeRuntimeModelStore(modelFile, setOf(digest, secondDigest))
    val backend = FakeInferenceBackend()
    private val registry = FakeRuntimeRegistry(
        primary = resolved(useCaseId, "profile-a", digest),
        secondary = resolved(secondUseCaseId, "profile-b", secondDigest),
    )
    val runtime = RuntimeOrchestrator(registry, store, backend)

    fun request(id: String, sessionId: io.github.daniele21.localllm.contracts.SessionId): GenerationRequest = GenerationRequest(
        requestId = RequestId(id),
        sessionId = sessionId,
        applicationId = applicationId,
        useCaseId = useCaseId,
        input = "prompt",
    )

    fun close() {
        backend.blockGeneration?.countDown()
        runtime.close()
        modelFile.delete()
    }

    companion object {
        private fun defaultGenerationDefaults() = GenerationDefaults(
            maxOutputTokens = 16,
            temperature = 0f,
            topP = 1f,
            topK = 0,
            seed = 42,
        )
    }

    private fun resolved(useCaseId: UseCaseId, profileId: String, modelDigest: ModelDigest): ResolvedUseCase {
        val model = GgufModelProfile(
            id = profileId,
            artifact = GgufArtifact(
                digest = modelDigest,
                fileName = "$profileId.gguf",
                sizeBytes = modelFile.length(),
                architecture = "qwen2",
                quantization = "Q4_K_M",
                source = ArtifactSource.Imported(profileId),
            ),
            contextSize = 512,
            batchSize = 128,
            microBatchSize = 64,
            cpuThreads = 2,
            batchThreads = 2,
            gpuLayers = 0,
        )
        val useCase = UseCaseProfile(
            id = "use-case-${useCaseId.value}",
            modelProfileId = profileId,
            systemPromptVersion = "v1",
            generationDefaults = generationDefaults,
            outputMode = OutputMode.TEXT,
            cachePolicy = UseCaseCachePolicy(0, false, false, false),
            healthSuiteId = "health",
        )
        return ResolvedUseCase(
            binding = AppModelBinding(applicationId, useCaseId, useCase.id),
            useCase = useCase,
            model = model,
        )
    }
}

private class FakeRuntimeRegistry(private val primary: ResolvedUseCase, private val secondary: ResolvedUseCase) : ModelProfileRegistry {
    override fun resolve(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase =
        if (useCaseId == primary.binding.useCaseId) primary else secondary
}

private class FakeRuntimeModelStore(private val file: File, private val available: Set<ModelDigest>) : ModelStore {
    var verificationCalls: Int = 0

    override fun find(digest: ModelDigest): StoredModel? = if (digest in available) {
        StoredModel(digest, file, file.length(), verified = false)
    } else {
        null
    }

    override fun import(source: File, artifact: GgufArtifact): StoredModel = error("Not used")

    override fun verify(digest: ModelDigest): VerificationResult {
        verificationCalls += 1
        return VerificationResult(true, digest, "valid")
    }

    override fun remove(digest: ModelDigest): Boolean = false

    override fun snapshot(): ModelStoreSnapshot = ModelStoreSnapshot(0, 0, emptyList())
}

private data class FakeBackendModel(
    override val digest: ModelDigest,
    override val profileId: String,
    override val loadDurationMs: Long = 12,
) : BackendModelHandle

private data class FakeBackendContext(override val model: BackendModelHandle, override val contextSize: Int) : BackendContextHandle

private class FakeInferenceBackend : InferenceBackend {
    override val id: String = "fake"
    var initializeCalls: Int = 0
    var loadCalls: Int = 0
    var unloadCalls: Int = 0
    var releaseCalls: Int = 0
    var cancelCalls: Int = 0
    var blockGeneration: CountDownLatch? = null
    var blockPlanning: CountDownLatch? = null
    var blockContextCreation: CountDownLatch? = null
    var generationStarted: CountDownLatch = CountDownLatch(1)
    var planningStarted: CountDownLatch = CountDownLatch(1)
    var contextCreationStarted: CountDownLatch = CountDownLatch(1)
    var generationCalls: Int = 0
    var plannedStopSequences: List<String> = emptyList()
    var generateFailureCode: String? = null
    var generateFailureAfterUnblockCode: String? = null
    val createdContextSizes = Collections.synchronizedList(mutableListOf<Int>())

    @Volatile var lastGenerationRequest: BackendGenerationRequest? = null

    override fun initialize() {
        initializeCalls += 1
    }

    override fun shutdown() = Unit

    override fun loadModel(source: BackendModelSource, profile: GgufModelProfile): BackendModelHandle {
        loadCalls += 1
        return FakeBackendModel(source.digest, profile.id)
    }

    override fun unloadModel(model: BackendModelHandle) {
        unloadCalls += 1
    }

    override fun modelCapabilities(model: BackendModelHandle) = BackendModelCapabilities(4_096, true)

    override fun planPrompt(model: BackendModelHandle, request: BackendPromptPlanningRequest): BackendPromptPlan {
        planningStarted.countDown()
        blockPlanning?.await()
        return fakePromptPlan(request).copy(stopSequences = plannedStopSequences)
    }

    override fun createContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendContextConfiguration,
    ): BackendContextHandle {
        contextCreationStarted.countDown()
        blockContextCreation?.await()
        createdContextSizes += configuration.contextSize
        return FakeBackendContext(model, configuration.contextSize)
    }

    override fun releaseContext(context: BackendContextHandle) {
        releaseCalls += 1
    }

    override fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome {
        generationCalls += 1
        generateFailureCode?.let { throw BackendException(it, "Invalid output constraint") }
        lastGenerationRequest = request
        generationStarted.countDown()
        blockGeneration?.await()
        generateFailureAfterUnblockCode?.let {
            throw BackendException(it, "Generation failed while cancellation was in flight")
        }
        if (!onChunk("hello ", 1)) {
            return BackendGenerationOutcome.Cancelled(BackendGenerationMetrics(2, 1, 5, 1))
        }
        if (!onChunk("world", 2)) {
            return BackendGenerationOutcome.Cancelled(BackendGenerationMetrics(2, 2, 5, 5))
        }
        return BackendGenerationOutcome.Completed(
            BackendGenerationMetrics(
                inputTokens = 2,
                outputTokens = 2,
                promptDurationMs = 5,
                generationDurationMs = 10,
            ),
        )
    }

    override fun cancel(requestId: String): Boolean {
        cancelCalls += 1
        blockGeneration?.countDown()
        return true
    }
}
