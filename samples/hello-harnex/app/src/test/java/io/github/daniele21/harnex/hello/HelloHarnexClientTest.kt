package io.github.daniele21.harnex.hello

import io.github.daniele21.localllm.contracts.ConsumerActivation
import io.github.daniele21.localllm.contracts.ConsumerActivationId
import io.github.daniele21.localllm.contracts.ConsumerActivationRequest
import io.github.daniele21.localllm.contracts.ConsumerActivationResult
import io.github.daniele21.localllm.contracts.ConsumerAssignedUseCase
import io.github.daniele21.localllm.contracts.ConsumerAssignedUseCasesResult
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneFailure
import io.github.daniele21.localllm.contracts.ConsumerDeactivationResult
import io.github.daniele21.localllm.contracts.ConsumerErrorCode
import io.github.daniele21.localllm.contracts.ConsumerFailure
import io.github.daniele21.localllm.contracts.ConsumerGenerationEvent
import io.github.daniele21.localllm.contracts.ConsumerGenerationHandle
import io.github.daniele21.localllm.contracts.ConsumerGenerationInput
import io.github.daniele21.localllm.contracts.ConsumerGenerationListener
import io.github.daniele21.localllm.contracts.ConsumerGenerationRequest
import io.github.daniele21.localllm.contracts.ConsumerGenerationStartResult
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraint
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.ConsumerPrepareRequest
import io.github.daniele21.localllm.contracts.ConsumerPrepareResult
import io.github.daniele21.localllm.contracts.ConsumerPreparedId
import io.github.daniele21.localllm.contracts.ConsumerPreparedSelection
import io.github.daniele21.localllm.contracts.ConsumerPublishedPreset
import io.github.daniele21.localllm.contracts.ConsumerPublishedPresetsResult
import io.github.daniele21.localllm.contracts.ConsumerSessionResult
import io.github.daniele21.localllm.contracts.EffectiveConsumerReasoningMode
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.UseCaseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HelloHarnexClientTest {
    @Test
    fun `generic text request forwards prompt unchanged with text output constraint`() {
        val runtime = FakeHelloHarnexRuntime()
        val client = HelloHarnexClient(runtime)
        try {
            client.run("Explain local AI", {}, {}, {})
            assertTrue(runtime.generationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val request = requireNotNull(runtime.lastGenerationRequest)
            assertEquals(
                ConsumerGenerationInput.Text("Explain local AI"),
                request.input,
            )
            assertEquals(ConsumerOutputConstraint.Text, request.outputConstraint)
        } finally {
            client.close()
            assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `second inference is rejected while first generation is active`() {
        val runtime = FakeHelloHarnexRuntime()
        val client = HelloHarnexClient(runtime)
        try {
            client.run("first", {}, {}, {})
            assertTrue(runtime.generationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            var second: Result<HelloInferenceResult>? = null
            client.run("second", {}, {}, { second = it })

            assertTrue(requireNotNull(second).isFailure)
            assertTrue(requireNotNull(second).exceptionOrNull() is IllegalStateException)
        } finally {
            client.close()
            assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `terminal generation failure releases session and activation`() {
        val runtime = FakeHelloHarnexRuntime()
        val client = HelloHarnexClient(runtime)
        try {
            val resultReady = CountDownLatch(1)
            var result: Result<HelloInferenceResult>? = null
            client.run(
                text = "test",
                onStatus = {},
                onAnswerDelta = {},
                onResult = {
                    result = it
                    resultReady.countDown()
                },
            )
            assertTrue(runtime.generationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            runtime.failGeneration(ConsumerErrorCode.RUNTIME_FAILURE, "boom")

            assertTrue(resultReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val failure = requireNotNull(result).exceptionOrNull() as HelloHarnexException
            assertEquals(HelloHarnexFailureKind.RUNTIME, failure.kind)
            assertEquals(1, runtime.closedSessions.get())
            assertEquals(1, runtime.deactivations.get())
        } finally {
            client.close()
            assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `close racing generation acceptance cancels and cleans up exactly once`() {
        val runtime = FakeHelloHarnexRuntime(blockGenerationReturn = true)
        val client = HelloHarnexClient(runtime)

        client.run("test", {}, {}, {})
        assertTrue(runtime.generationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        client.close()
        runtime.allowGenerationToReturn()

        assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, runtime.cancelCount.get())
        assertEquals(1, runtime.closedSessions.get())
        assertEquals(1, runtime.deactivations.get())

        runtime.failGeneration(ConsumerErrorCode.CANCELLED, "late terminal callback")
        client.close()
        assertEquals(1, runtime.closeCount.get())
    }

    @Test
    fun `close before queued inference starts performs no host work`() {
        val runtime = FakeHelloHarnexRuntime()
        val executor = Executors.newSingleThreadExecutor()
        val blockerStarted = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        executor.execute {
            blockerStarted.countDown()
            releaseBlocker.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val client = HelloHarnexClient(runtime, executor)
        try {
            assertTrue(blockerStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            client.run("test", {}, {}, {})
            client.close()
            releaseBlocker.countDown()

            assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(0, runtime.assignmentCalls.get())
            assertEquals(0, runtime.cancelCount.get())
        } finally {
            releaseBlocker.countDown()
            client.close()
        }
    }

    @Test
    fun `authorization rejection is surfaced as actionable failure`() {
        val runtime = FakeHelloHarnexRuntime(
            assignments = ConsumerAssignedUseCasesResult.Rejected(
                ConsumerControlPlaneFailure(
                    ConsumerControlPlaneErrorCode.APPLICATION_NOT_AUTHORIZED,
                    "authorization required",
                ),
            ),
        )
        val client = HelloHarnexClient(runtime)
        try {
            val resultReady = CountDownLatch(1)
            var result: Result<HelloInferenceResult>? = null
            client.run(
                "test",
                {},
                {},
                {
                    result = it
                    resultReady.countDown()
                },
            )

            assertTrue(resultReady.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            val failure = requireNotNull(result).exceptionOrNull() as HelloHarnexException
            assertEquals(HelloHarnexFailureKind.AUTHORIZATION_REQUIRED, failure.kind)
            assertTrue(failure.userMessage.contains("authorize", ignoreCase = true))
        } finally {
            client.close()
            assertTrue(runtime.closed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private class FakeHelloHarnexRuntime(
        private val assignments: ConsumerAssignedUseCasesResult = validAssignments(),
        private val blockGenerationReturn: Boolean = false,
    ) : HelloHarnexRuntime {
        val generationStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelCount = AtomicInteger(0)
        val closedSessions = AtomicInteger(0)
        val deactivations = AtomicInteger(0)
        val closeCount = AtomicInteger(0)
        val assignmentCalls = AtomicInteger(0)

        @Volatile
        var lastGenerationRequest: ConsumerGenerationRequest? = null
            private set

        private val generationReturnAllowed = CountDownLatch(if (blockGenerationReturn) 1 else 0)
        private var generationRequestId: RequestId? = null
        private var generationListener: ConsumerGenerationListener? = null

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun assignedUseCases(): ConsumerAssignedUseCasesResult {
            assignmentCalls.incrementAndGet()
            return assignments
        }

        override fun publishedPresets(useCaseId: UseCaseId): ConsumerPublishedPresetsResult = ConsumerPublishedPresetsResult.Available(
            useCaseId = useCaseId,
            bindingRevision = 1,
            presets = listOf(
                ConsumerPublishedPreset(
                    preset = PRESET,
                    displayName = "Quality",
                    description = "General-purpose text preset",
                    isDefault = true,
                ),
            ),
        )

        override fun activate(request: ConsumerActivationRequest): ConsumerActivationResult = ConsumerActivationResult.Activated(
            ConsumerActivation(
                activationId = ConsumerActivationId("activation-1"),
                useCaseId = request.useCaseId,
                useCaseRevision = request.useCaseRevision,
                bindingRevision = request.bindingRevision,
                preset = request.preset,
            ),
        )

        override fun deactivate(activationId: ConsumerActivationId): ConsumerDeactivationResult {
            deactivations.incrementAndGet()
            return ConsumerDeactivationResult.Released
        }

        override fun prepare(request: ConsumerPrepareRequest): ConsumerPrepareResult = ConsumerPrepareResult.Prepared(
            ConsumerPreparedSelection(
                preparedId = ConsumerPreparedId("prepared-1"),
                useCaseId = request.useCaseId,
                capabilityRevision = "capability-1",
                preset = PRESET,
                reasoningMode = EffectiveConsumerReasoningMode.DISABLED,
                outputConstraint = ConsumerOutputConstraintKind.TEXT,
                sessionKind = SessionKind.STATELESS,
            ),
        )

        override fun createSession(preparedId: ConsumerPreparedId): ConsumerSessionResult =
            ConsumerSessionResult.Created(SessionId("session-1"))

        override fun generate(request: ConsumerGenerationRequest, listener: ConsumerGenerationListener): ConsumerGenerationStartResult {
            lastGenerationRequest = request
            generationRequestId = request.requestId
            generationListener = listener
            generationStarted.countDown()
            if (blockGenerationReturn) {
                check(generationReturnAllowed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Timed out waiting to release fake generation"
                }
            }
            return ConsumerGenerationStartResult.Accepted(
                object : ConsumerGenerationHandle {
                    override val requestId: RequestId = request.requestId

                    override fun cancel() {
                        cancelCount.incrementAndGet()
                    }
                },
            )
        }

        override fun closeSession(sessionId: SessionId) {
            closedSessions.incrementAndGet()
        }

        override fun close() {
            closeCount.incrementAndGet()
            closed.countDown()
        }

        fun allowGenerationToReturn() {
            generationReturnAllowed.countDown()
        }

        fun failGeneration(code: ConsumerErrorCode, message: String) {
            val requestId = requireNotNull(generationRequestId)
            requireNotNull(generationListener).onEvent(
                ConsumerGenerationEvent.Failed(
                    requestId = requestId,
                    failure = ConsumerFailure(code, message),
                ),
            )
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        val USE_CASE = UseCaseId("generic-text-generation")
        val PRESET = InferencePresetRef(InferencePresetId("qwen35-text-quality"), 1)

        fun validAssignments(): ConsumerAssignedUseCasesResult = ConsumerAssignedUseCasesResult.Available(
            listOf(
                ConsumerAssignedUseCase(
                    useCaseId = USE_CASE,
                    useCaseRevision = 1,
                    bindingRevision = 1,
                    displayName = "Generic text generation",
                    description = "Run bounded local text generation",
                    isDefault = true,
                ),
            ),
        )
    }
}
