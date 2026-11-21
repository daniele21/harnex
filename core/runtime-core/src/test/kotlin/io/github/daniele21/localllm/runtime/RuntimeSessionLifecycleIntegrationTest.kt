package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.SessionOptions
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeSessionLifecycleIntegrationTest {
    @Test
    fun `two stateless turns release both contexts and load the model once`() {
        val backend = DeterministicFakeInferenceBackend()
        val fixture = RuntimeSessionLifecycleFixture(backend)
        try {
            assertTrue(fixture.runtime.prepare(fixture.applicationId, fixture.useCaseId).ready)
            repeat(2) { turn ->
                val session = fixture.runtime.createSession(
                    fixture.applicationId,
                    fixture.useCaseId,
                    SessionOptions(kind = SessionKind.STATELESS),
                )
                assertTrue(fixture.generateAndAwait("turn-$turn", session) is GenerationEvent.Completed)
                fixture.runtime.closeSession(session)
                assertTrue(waitUntil { fixture.runtime.runtimeSnapshot().activeSessions == 0 })
                assertEquals(turn + 1, backend.releaseContextCalls)
            }
            assertEquals(1, backend.loadCalls)
            assertEquals(0, backend.unloadCalls)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `close waits for active generation rejects new work and releases exactly once after drain`() {
        val delegate = DeterministicFakeInferenceBackend()
        val backend = BlockingInferenceBackend(delegate)
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)

        fixture.runtime.generate(
            fixture.request("active", session),
            GenerationListener { event ->
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) terminal.countDown()
            },
        )
        assertTrue(backend.started.await(2, TimeUnit.SECONDS))

        fixture.runtime.closeSession(session)
        fixture.runtime.closeSession(session)

        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(0, delegate.releaseContextCalls)

        val rejected = Collections.synchronizedList(mutableListOf<GenerationEvent>())
        fixture.runtime.generate(
            fixture.request("rejected", session),
            GenerationListener { event -> rejected += event },
        )
        assertTrue(rejected.single() is GenerationEvent.Failed)
        assertEquals(1, backend.generateCalls.get())

        backend.allowCompletion.countDown()
        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertTrue(waitUntil { fixture.runtime.runtimeSnapshot().activeSessions == 0 })
        assertEquals(1, delegate.releaseContextCalls)

        fixture.runtime.closeSession(session)
        assertEquals(1, delegate.releaseContextCalls)
        fixture.close()
    }

    @Test
    fun `terminal callback can close its session after request ownership is drained`() {
        val backend = DeterministicFakeInferenceBackend()
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        val terminal = CountDownLatch(1)
        var activeSessionsAtTerminal = -1

        fixture.runtime.generate(
            fixture.request("terminal-close", session),
            GenerationListener { event ->
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) {
                    fixture.runtime.closeSession(session)
                    activeSessionsAtTerminal = fixture.runtime.runtimeSnapshot().activeSessions
                    terminal.countDown()
                }
            },
        )

        assertTrue(terminal.await(2, TimeUnit.SECONDS))
        assertEquals(0, activeSessionsAtTerminal)
        assertEquals(1, backend.releaseContextCalls)
        fixture.close()
    }

    @Test
    fun `failed physical release rolls lifecycle back and a repeated close retries cleanup`() {
        val delegate = DeterministicFakeInferenceBackend()
        val backend = ReleaseFailOnceBackend(delegate)
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)

        assertTrue(fixture.generateAndAwait("materialize", session) is GenerationEvent.Completed)

        val firstClose = runCatching { fixture.runtime.closeSession(session) }
        assertTrue(firstClose.isFailure)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(1, backend.releaseAttempts.get())

        fixture.runtime.closeSession(session)

        assertEquals(0, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(2, backend.releaseAttempts.get())
        assertEquals(1, delegate.releaseContextCalls)
        fixture.close()
    }

    @Test
    fun `context creation failure is request scoped and the same session can retry`() {
        val backend = DeterministicFakeInferenceBackend().apply {
            contextCreationFailure = FakeBackendFailure("CONTEXT_CREATE_FAILED", "synthetic context creation failure")
        }
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)

        val failed = fixture.generateAndAwait("context-failure", session)

        assertTrue(failed is GenerationEvent.Failed)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(1, backend.createContextCalls)
        assertEquals(0, backend.generateCalls)

        backend.contextCreationFailure = null
        val retried = fixture.generateAndAwait("context-retry", session)

        assertTrue(retried is GenerationEvent.Completed)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(2, backend.createContextCalls)
        assertEquals(1, backend.generateCalls)
        fixture.close()
    }

    @Test
    fun `generation failure is request scoped and the same session can retry`() {
        val backend = DeterministicFakeInferenceBackend().apply {
            generationFailure = FakeBackendFailure("GENERATION_FAILED", "synthetic generation failure")
            generationFailureAfterChunks = 1
        }
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)

        val failed = fixture.generateAndAwait("generation-failure", session)

        assertTrue(failed is GenerationEvent.Failed)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(1, backend.generateCalls)

        backend.generationFailure = null
        backend.generationFailureAfterChunks = null
        val retried = fixture.generateAndAwait("generation-retry", session)

        assertTrue(retried is GenerationEvent.Completed)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(2, backend.generateCalls)
        fixture.close()
    }

    @Test
    fun `context release failure retains session ownership until cleanup retry succeeds`() {
        val backend = DeterministicFakeInferenceBackend()
        val fixture = RuntimeSessionLifecycleFixture(backend)
        val session = fixture.runtime.createSession(fixture.applicationId, fixture.useCaseId)
        assertTrue(fixture.generateAndAwait("materialize-for-release", session) is GenerationEvent.Completed)
        backend.contextReleaseFailure = FakeBackendFailure("CONTEXT_RELEASE_FAILED", "synthetic context release failure")

        val firstClose = runCatching { fixture.runtime.closeSession(session) }

        assertTrue(firstClose.isFailure)
        assertEquals(1, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(1, backend.releaseContextCalls)

        backend.contextReleaseFailure = null
        fixture.runtime.closeSession(session)

        assertEquals(0, fixture.runtime.runtimeSnapshot().activeSessions)
        assertEquals(2, backend.releaseContextCalls)
        fixture.close()
    }

    private fun waitUntil(timeoutMs: Long = 2_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}

private class BlockingInferenceBackend(private val delegate: DeterministicFakeInferenceBackend) : InferenceBackend by delegate {
    val started = CountDownLatch(1)
    val allowCompletion = CountDownLatch(1)
    val generateCalls = AtomicInteger(0)

    override fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome {
        generateCalls.incrementAndGet()
        started.countDown()
        check(allowCompletion.await(2, TimeUnit.SECONDS)) { "Timed out waiting to release blocked generation" }
        return delegate.generate(context, request, onChunk)
    }
}

private class ReleaseFailOnceBackend(private val delegate: DeterministicFakeInferenceBackend) : InferenceBackend by delegate {
    val releaseAttempts = AtomicInteger(0)

    override fun releaseContext(context: BackendContextHandle) {
        if (releaseAttempts.incrementAndGet() == 1) {
            throw BackendException("RELEASE_FAILED", "synthetic release failure")
        }
        delegate.releaseContext(context)
    }
}

private class RuntimeSessionLifecycleFixture(backend: InferenceBackend) {
    val applicationId = ApplicationId("lifecycle-app")
    val useCaseId = UseCaseId("lifecycle-use-case")
    private val digest = ModelDigest("f".repeat(64))
    private val modelFile = File.createTempFile("session-lifecycle-model", ".gguf").apply {
        writeText("model")
        deleteOnExit()
    }
    private val resolved = resolvedUseCase()
    private val registry = object : ModelProfileRegistry {
        override fun resolve(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase = resolved
    }
    val runtime = RuntimeOrchestrator(
        registry = registry,
        modelStore = SessionLifecycleModelStore(modelFile, digest),
        backend = backend,
    )

    fun request(id: String, sessionId: SessionId): GenerationRequest = GenerationRequest(
        requestId = RequestId(id),
        sessionId = sessionId,
        applicationId = applicationId,
        useCaseId = useCaseId,
        input = "prompt",
    )

    fun generateAndAwait(id: String, sessionId: SessionId): GenerationEvent {
        val terminal = CountDownLatch(1)
        var terminalEvent: GenerationEvent? = null
        runtime.generate(
            request(id, sessionId),
            GenerationListener { event ->
                if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) {
                    terminalEvent = event
                    terminal.countDown()
                }
            },
        )
        check(terminal.await(2, TimeUnit.SECONDS)) { "Generation did not reach a terminal event" }
        check(waitUntilRuntimeDrained()) { "Generation terminal event was emitted before runtime lifecycle drained" }
        return checkNotNull(terminalEvent)
    }

    fun close() {
        runtime.close()
        modelFile.delete()
    }

    private fun waitUntilRuntimeDrained(timeoutMs: Long = 2_000): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (runtime.runtimeSnapshot().state != io.github.daniele21.localllm.contracts.RuntimeState.GENERATING) return true
            Thread.sleep(10)
        }
        return runtime.runtimeSnapshot().state != io.github.daniele21.localllm.contracts.RuntimeState.GENERATING
    }

    private fun resolvedUseCase(): ResolvedUseCase {
        val model = GgufModelProfile(
            id = "lifecycle-profile",
            artifact = GgufArtifact(
                digest = digest,
                fileName = "lifecycle.gguf",
                sizeBytes = modelFile.length(),
                architecture = "qwen2",
                quantization = "Q4_K_M",
                source = ArtifactSource.Imported("lifecycle-test"),
            ),
            contextSize = 512,
            batchSize = 128,
            microBatchSize = 64,
            cpuThreads = 2,
            batchThreads = 2,
            gpuLayers = 0,
        )
        val useCase = UseCaseProfile(
            id = "lifecycle-use-case-profile",
            modelProfileId = model.id,
            systemPromptVersion = "v1",
            generationDefaults = GenerationDefaults(8, 0f, seed = 7),
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

private class SessionLifecycleModelStore(private val file: File, private val digest: ModelDigest) : ModelStore {
    override fun find(digest: ModelDigest): StoredModel? = if (digest == this.digest) {
        StoredModel(digest, file, file.length(), verified = true)
    } else {
        null
    }

    override fun import(source: File, artifact: GgufArtifact): StoredModel = error("Not used")

    override fun verify(digest: ModelDigest): VerificationResult = VerificationResult(true, digest, "valid")

    override fun remove(digest: ModelDigest): Boolean = false

    override fun snapshot(): ModelStoreSnapshot = ModelStoreSnapshot(0, 0, emptyList())
}
