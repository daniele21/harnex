package io.github.daniele21.localllm.transport.binder.client

import io.github.daniele21.localllm.contracts.ConsumerAssignedUseCasesResult
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.transport.binder.contract.BinderProtocolV1
import io.github.daniele21.localllm.transport.binder.contract.ConsumerControlPlaneRequestParcel
import io.github.daniele21.localllm.transport.binder.contract.ConsumerControlPlaneResultParcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderConsumerControlPlaneInvalidationTest {
    @Test
    fun `stale endpoint is rejected before discovery request reaches the host`() {
        val service = FakeSharedRuntimeRemoteService()
        var invoked = false
        val consumer = object : ConsumerSharedRuntimeRemoteService by service.consumer {
            override fun discoverUseCases(
                request: ConsumerControlPlaneRequestParcel,
                callback: (ConsumerControlPlaneResultParcel) -> Unit,
            ) {
                invoked = true
            }
        }
        val host = object : SharedRuntimeRemoteService by service {
            override val consumer: ConsumerSharedRuntimeRemoteService = consumer
        }
        val endpoint = RegisteredSharedRuntimeEndpoint(
            host,
            successfulRegistration().clientToken!!,
            connectionEpoch = 40L,
        )
        var reads = 0
        val adapter = BinderConsumerControlPlaneAdapter(
            endpointProvider = { if (++reads == 1) endpoint else null },
            enabledFeaturesProvider = { setOf(BinderProtocolV1.FEATURE_CONSUMER_CONTROL_PLANE_V1) },
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 100L,
            correlationIds = CorrelationIdSource { "stale-discovery-op" },
        )

        val result = adapter.assignedUseCases()

        assertTrue(result is ConsumerAssignedUseCasesResult.Rejected)
        assertEquals(
            ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
            (result as ConsumerAssignedUseCasesResult.Rejected).failure.code,
        )
        assertFalse(invoked)
    }

    @Test(timeout = 2_000)
    fun `blank endpoint invalidation reason immediately rejects discovery`() {
        val invalidations = FakeEndpointInvalidations()
        val originalService = FakeSharedRuntimeRemoteService()
        val consumer = object : ConsumerSharedRuntimeRemoteService by originalService.consumer {
            override fun discoverUseCases(
                request: ConsumerControlPlaneRequestParcel,
                callback: (ConsumerControlPlaneResultParcel) -> Unit,
            ) {
                invalidations.invalidate(24L, "")
            }
        }
        val service = object : SharedRuntimeRemoteService by originalService {
            override val consumer: ConsumerSharedRuntimeRemoteService = consumer
        }
        val endpoint = RegisteredSharedRuntimeEndpoint(
            service,
            successfulRegistration().clientToken!!,
            connectionEpoch = 24L,
        )
        val adapter = BinderConsumerControlPlaneAdapter(
            endpointProvider = { endpoint },
            enabledFeaturesProvider = { setOf(BinderProtocolV1.FEATURE_CONSUMER_CONTROL_PLANE_V1) },
            endpointInvalidations = invalidations,
            blockingCallGuard = BlockingCallGuard {},
            operationTimeoutMillis = 60_000L,
            correlationIds = CorrelationIdSource { "discovery-blank-op" },
        )

        val result = adapter.assignedUseCases()

        assertTrue(result is ConsumerAssignedUseCasesResult.Rejected)
        assertEquals(
            ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
            (result as ConsumerAssignedUseCasesResult.Rejected).failure.code,
        )
    }
}
