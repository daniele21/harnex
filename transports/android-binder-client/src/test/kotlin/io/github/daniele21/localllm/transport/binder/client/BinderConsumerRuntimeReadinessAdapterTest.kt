package io.github.daniele21.localllm.transport.binder.client

import io.github.daniele21.localllm.contracts.ConsumerActivationId
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.contracts.ConsumerPreparationAction
import io.github.daniele21.localllm.contracts.ConsumerRuntimePhase
import io.github.daniele21.localllm.contracts.ConsumerRuntimeReadinessResult
import io.github.daniele21.localllm.transport.binder.contract.BinderProtocolV1
import io.github.daniele21.localllm.transport.binder.contract.ConsumerRuntimeReadinessResultParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderConsumerRuntimeReadinessAdapterTest {
    private val token = successfulRegistration().clientToken!!
    private val activationId = ConsumerActivationId("activation-opaque")

    @Test
    fun `readiness round trip keeps only consumer-safe lifecycle state`() {
        val service = FakeSharedRuntimeRemoteService().apply {
            consumerRuntimeReadinessHandler = { request, callback ->
                assertEquals(activationId.value, request.activationId)
                callback(
                    ConsumerRuntimeReadinessResultParcel(
                        operationId = request.operationId,
                        activationId = activationId.value,
                        phaseTag = ConsumerRuntimePhase.PREPARING.name,
                        preparationActionTag = ConsumerPreparationAction.LOADING.name,
                    ),
                )
            }
        }
        val adapter = adapter(service)

        val result = adapter.runtimeReadiness(activationId)

        assertTrue(result is ConsumerRuntimeReadinessResult.Available)
        val readiness = (result as ConsumerRuntimeReadinessResult.Available).readiness
        assertEquals(activationId, readiness.activationId)
        assertEquals(ConsumerRuntimePhase.PREPARING, readiness.phase)
        assertEquals(ConsumerPreparationAction.LOADING, readiness.preparationAction)
        assertTrue(service.lastRuntimeReadinessRequest.toString().contains(activationId.value))
        assertTrue(!service.lastRuntimeReadinessRequest.toString().contains("digest", ignoreCase = true))
        assertTrue(!service.lastRuntimeReadinessRequest.toString().contains("model", ignoreCase = true))
    }

    @Test
    fun `readiness fails closed when feature was not negotiated`() {
        val service = FakeSharedRuntimeRemoteService()
        val adapter = BinderConsumerRuntimeReadinessAdapter(
            endpointProvider = { RegisteredSharedRuntimeEndpoint(service, token) },
            enabledFeaturesProvider = { emptySet() },
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 100,
            correlationIds = CorrelationIdSource { "readiness-op" },
        )

        val result = adapter.runtimeReadiness(activationId)

        assertTrue(result is ConsumerRuntimeReadinessResult.Rejected)
        assertEquals(
            ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE,
            (result as ConsumerRuntimeReadinessResult.Rejected).failure.code,
        )
    }

    @Test
    fun `endpoint invalidation fails in-flight readiness closed`() {
        val invalidations = FakeEndpointInvalidations()
        val service = FakeSharedRuntimeRemoteService()
        val endpoint = RegisteredSharedRuntimeEndpoint(service, token, connectionEpoch = 9L)
        service.consumerRuntimeReadinessHandler = { _, _ -> invalidations.invalidate(9L, "host died") }
        val adapter = BinderConsumerRuntimeReadinessAdapter(
            endpointProvider = { endpoint },
            enabledFeaturesProvider = { setOf(BinderProtocolV1.FEATURE_CONSUMER_RUNTIME_READINESS_V1) },
            endpointInvalidations = invalidations,
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 5_000,
            correlationIds = CorrelationIdSource { "readiness-op" },
        )

        val result = adapter.runtimeReadiness(activationId)

        assertTrue(result is ConsumerRuntimeReadinessResult.Rejected)
        assertEquals(
            ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
            (result as ConsumerRuntimeReadinessResult.Rejected).failure.code,
        )
    }

    @Test(timeout = 2_000)
    fun `blank endpoint invalidation reason immediately rejects in-flight readiness`() {
        val invalidations = FakeEndpointInvalidations()
        val service = FakeSharedRuntimeRemoteService()
        val endpoint = RegisteredSharedRuntimeEndpoint(service, token, connectionEpoch = 21L)
        service.consumerRuntimeReadinessHandler = { _, _ -> invalidations.invalidate(21L, "") }
        val adapter = BinderConsumerRuntimeReadinessAdapter(
            endpointProvider = { endpoint },
            enabledFeaturesProvider = { setOf(BinderProtocolV1.FEATURE_CONSUMER_RUNTIME_READINESS_V1) },
            endpointInvalidations = invalidations,
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 60_000L,
            correlationIds = CorrelationIdSource { "readiness-blank-op" },
        )

        val result = adapter.runtimeReadiness(activationId)

        assertTrue(result is ConsumerRuntimeReadinessResult.Rejected)
        assertEquals(
            ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
            (result as ConsumerRuntimeReadinessResult.Rejected).failure.code,
        )
    }

    private fun adapter(service: FakeSharedRuntimeRemoteService): BinderConsumerRuntimeReadinessAdapter =
        BinderConsumerRuntimeReadinessAdapter(
            endpointProvider = { RegisteredSharedRuntimeEndpoint(service, token) },
            enabledFeaturesProvider = { setOf(BinderProtocolV1.FEATURE_CONSUMER_RUNTIME_READINESS_V1) },
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 100,
            correlationIds = CorrelationIdSource { "readiness-op" },
        )
}
