package io.github.daniele21.localllm.transport.binder.client

import io.github.daniele21.localllm.contracts.ConsumerCapabilityErrorCode
import io.github.daniele21.localllm.contracts.ConsumerCapabilityResult
import io.github.daniele21.localllm.contracts.ConsumerLimits
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.ConsumerPrepareRequest
import io.github.daniele21.localllm.contracts.ConsumerPrepareResult
import io.github.daniele21.localllm.contracts.ConsumerPreparedId
import io.github.daniele21.localllm.contracts.ConsumerPreparedSelection
import io.github.daniele21.localllm.contracts.ConsumerReasoningCapability
import io.github.daniele21.localllm.contracts.ConsumerSelectionRequest
import io.github.daniele21.localllm.contracts.ConsumerSessionResult
import io.github.daniele21.localllm.contracts.EffectiveConsumerReasoningMode
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.UseCaseCapabilities
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.contracts.UseCaseReadiness
import io.github.daniele21.localllm.transport.binder.contract.ConsumerResultParcel
import io.github.daniele21.localllm.transport.binder.contract.toConsumerWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderConsumerLifecycleAdapterTest {
    private val token = successfulRegistration().clientToken!!
    private val useCaseId = UseCaseId("document-pii-detection")

    @Test
    fun `capabilities round trip exposes only consumer policy`() {
        val service = FakeSharedRuntimeRemoteService().apply {
            consumerCapabilitiesHandler = { request, callback ->
                assertEquals(useCaseId.value, request.useCaseId)
                callback(
                    ConsumerResultParcel(
                        operationId = request.operationId,
                        capabilities = capabilities().toConsumerWire(),
                    ),
                )
            }
        }
        val adapter = adapter(service)

        val result = adapter.capabilities(useCaseId)

        assertTrue(result is ConsumerCapabilityResult.Available)
        val available = result as ConsumerCapabilityResult.Available
        assertEquals(useCaseId, available.capabilities.useCaseId)
        assertEquals("cap-rev-1", available.capabilities.capabilityRevision)
        assertEquals(setOf(ConsumerOutputConstraintKind.JSON_SCHEMA), available.capabilities.outputConstraints)
        assertEquals(SessionKind.STATELESS, available.capabilities.defaultSessionKind)
    }

    @Test
    fun `capabilities fail safely when disconnected`() {
        val adapter = BinderConsumerLifecycleAdapter(
            endpointProvider = { null },
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 10,
            correlationIds = deterministicIds(),
        )

        val result = adapter.capabilities(useCaseId)

        assertTrue(result is ConsumerCapabilityResult.Rejected)
        assertEquals(
            ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE,
            (result as ConsumerCapabilityResult.Rejected).code,
        )
    }

    @Test
    fun `prepare and session preserve opaque consumer identities`() {
        val prepared = preparedSelection()
        val service = FakeSharedRuntimeRemoteService().apply {
            consumerPrepareHandler = { request, callback ->
                assertEquals(useCaseId.value, request.useCaseId)
                assertEquals("cap-rev-1", request.selection?.capabilityRevision)
                callback(
                    ConsumerResultParcel(
                        operationId = request.operationId,
                        preparedSelection = prepared.toConsumerWire(),
                    ),
                )
            }
            consumerOpenSessionHandler = { request, callback ->
                assertEquals(prepared.preparedId.value, request.preparedId)
                callback(
                    ConsumerResultParcel(
                        operationId = request.operationId,
                        externalSessionId = request.externalSessionId,
                    ),
                )
            }
        }
        val adapter = adapter(service)

        val prepareResult = adapter.prepare(
            ConsumerPrepareRequest(
                useCaseId,
                ConsumerSelectionRequest(capabilityRevision = "cap-rev-1"),
            ),
        )
        assertEquals(ConsumerPrepareResult.Prepared(prepared), prepareResult)

        val sessionResult = adapter.createSession(prepared.preparedId)
        assertTrue(sessionResult is ConsumerSessionResult.Created)
        val sessionId = (sessionResult as ConsumerSessionResult.Created).sessionId
        assertEquals("id-3", sessionId.value)

        adapter.closeSession(sessionId)
        adapter.closeSession(sessionId)
        assertEquals(1, service.consumerCloseSessionCalls)
    }

    @Test
    fun `endpoint invalidation fails an in-flight consumer lifecycle call closed`() {
        val invalidations = FakeEndpointInvalidations()
        val service = FakeSharedRuntimeRemoteService()
        val endpoint = RegisteredSharedRuntimeEndpoint(service, token, connectionEpoch = 7L)
        service.consumerCapabilitiesHandler = { _, _ -> invalidations.invalidate(7L, "host died") }
        val adapter = BinderConsumerLifecycleAdapter(
            endpointProvider = { endpoint },
            endpointInvalidations = invalidations,
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 5_000,
            correlationIds = deterministicIds(),
        )

        val result = adapter.capabilities(useCaseId)

        assertTrue(result is ConsumerCapabilityResult.Rejected)
        assertEquals(
            ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE,
            (result as ConsumerCapabilityResult.Rejected).code,
        )
    }

    @Test(timeout = 2_000)
    fun `blank endpoint invalidation reason immediately rejects in-flight capabilities`() {
        val invalidations = FakeEndpointInvalidations()
        val service = FakeSharedRuntimeRemoteService()
        val endpoint = RegisteredSharedRuntimeEndpoint(service, token, connectionEpoch = 22L)
        service.consumerCapabilitiesHandler = { _, _ -> invalidations.invalidate(22L, "") }
        val adapter = BinderConsumerLifecycleAdapter(
            endpointProvider = { endpoint },
            endpointInvalidations = invalidations,
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 60_000L,
            correlationIds = deterministicIds(),
        )

        val result = adapter.capabilities(useCaseId)

        assertTrue(result is ConsumerCapabilityResult.Rejected)
        assertEquals(
            ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE,
            (result as ConsumerCapabilityResult.Rejected).code,
        )
    }

    private fun adapter(service: FakeSharedRuntimeRemoteService): BinderConsumerLifecycleAdapter = BinderConsumerLifecycleAdapter(
        endpointProvider = { RegisteredSharedRuntimeEndpoint(service, token) },
        blockingCallGuard = BlockingCallGuard {},
        operationTimeoutMillis = 100,
        correlationIds = deterministicIds(),
    )

    private fun capabilities() = UseCaseCapabilities(
        useCaseId = useCaseId,
        readiness = UseCaseReadiness.READY,
        presets = emptyList(),
        defaultPreset = null,
        reasoning = ConsumerReasoningCapability.NOT_SUPPORTED,
        outputConstraints = setOf(ConsumerOutputConstraintKind.JSON_SCHEMA),
        defaultOutputConstraint = ConsumerOutputConstraintKind.JSON_SCHEMA,
        sessionKinds = setOf(SessionKind.STATELESS),
        defaultSessionKind = SessionKind.STATELESS,
        limits = ConsumerLimits(32_768, 128, 32_768),
        capabilityRevision = "cap-rev-1",
    )

    private fun preparedSelection() = ConsumerPreparedSelection(
        preparedId = ConsumerPreparedId("prepared-1"),
        useCaseId = useCaseId,
        capabilityRevision = "cap-rev-1",
        preset = null,
        reasoningMode = EffectiveConsumerReasoningMode.DISABLED,
        outputConstraint = ConsumerOutputConstraintKind.JSON_SCHEMA,
        sessionKind = SessionKind.STATELESS,
    )

    private fun deterministicIds(): CorrelationIdSource {
        var next = 0
        return CorrelationIdSource {
            next += 1
            "id-$next"
        }
    }
}
