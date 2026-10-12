package io.github.daniele21.localllm.transport.binder.client

import android.os.DeadObjectException
import android.os.RemoteException
import io.github.daniele21.localllm.contracts.ConsumerActivationId
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneFailure
import io.github.daniele21.localllm.contracts.ConsumerRuntimeReadinessClient
import io.github.daniele21.localllm.contracts.ConsumerRuntimeReadinessResult
import io.github.daniele21.localllm.transport.binder.contract.BinderProtocolV1
import io.github.daniele21.localllm.transport.binder.contract.ConsumerControlPlaneRequestParcel
import io.github.daniele21.localllm.transport.binder.contract.ConsumerRuntimeReadinessResultParcel
import io.github.daniele21.localllm.transport.binder.contract.toCoreRuntimeReadinessResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BinderConsumerRuntimeReadinessAdapter(
    private val endpointProvider: () -> RegisteredSharedRuntimeEndpoint?,
    private val enabledFeaturesProvider: () -> Set<String>,
    private val endpointInvalidations: SharedRuntimeEndpointInvalidationSource? = null,
    private val blockingCallGuard: BlockingCallGuard = AndroidMainThreadBlockingCallGuard,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val correlationIds: CorrelationIdSource = CorrelationIdSource { UUID.randomUUID().toString() },
) : ConsumerRuntimeReadinessClient {
    init {
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
    }

    override fun runtimeReadiness(activationId: ConsumerActivationId): ConsumerRuntimeReadinessResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return transportFailure()
        if (BinderProtocolV1.FEATURE_CONSUMER_RUNTIME_READINESS_V1 !in enabledFeaturesProvider()) {
            return featureUnavailable()
        }
        val operationId = correlationIds.nextId()
        val request =
            ConsumerControlPlaneRequestParcel(
                clientToken = endpoint.clientToken,
                operationId = operationId,
                activationId = activationId.value,
            )
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.runtimeReadiness(request, callback) }
        return when (outcome) {
            is RuntimeReadinessRemoteOutcome.Received -> {
                if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                    transportFailure()
                } else {
                    runCatching { outcome.result.toCoreRuntimeReadinessResult() }.getOrElse { transportFailure() }
                }
            }

            else -> transportFailure()
        }
    }

    private fun await(
        endpoint: RegisteredSharedRuntimeEndpoint,
        call: ((ConsumerRuntimeReadinessResultParcel) -> Unit) -> Unit,
    ): RuntimeReadinessRemoteOutcome {
        val waiter = RuntimeReadinessCallbackWaiter()
        val subscription = endpointInvalidations?.addListener { epoch, _ ->
            if (epoch == endpoint.connectionEpoch) waiter.disconnect()
        }
        return try {
            call(waiter::complete)
            waiter.await(operationTimeoutMillis)
        } catch (_: DeadObjectException) {
            RuntimeReadinessRemoteOutcome.Disconnected
        } catch (_: RemoteException) {
            RuntimeReadinessRemoteOutcome.TransportFailure
        } finally {
            subscription?.close()
        }
    }

    private fun isCurrent(endpoint: RegisteredSharedRuntimeEndpoint): Boolean =
        endpointProvider()?.connectionEpoch == endpoint.connectionEpoch

    private companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 120_000L
    }
}

private sealed interface RuntimeReadinessRemoteOutcome {
    data class Received(val result: ConsumerRuntimeReadinessResultParcel) : RuntimeReadinessRemoteOutcome

    data object Timeout : RuntimeReadinessRemoteOutcome

    data object Disconnected : RuntimeReadinessRemoteOutcome

    data object TransportFailure : RuntimeReadinessRemoteOutcome
}

private class RuntimeReadinessCallbackWaiter {
    private val latch = CountDownLatch(1)
    private val outcome = AtomicReference<RuntimeReadinessRemoteOutcome?>(null)

    fun complete(result: ConsumerRuntimeReadinessResultParcel) = finish(RuntimeReadinessRemoteOutcome.Received(result))

    fun disconnect() {
        finish(RuntimeReadinessRemoteOutcome.Disconnected)
    }

    fun await(timeoutMillis: Long): RuntimeReadinessRemoteOutcome = if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        requireNotNull(outcome.get())
    } else {
        RuntimeReadinessRemoteOutcome.Timeout
    }

    private fun finish(value: RuntimeReadinessRemoteOutcome) {
        if (outcome.compareAndSet(null, value)) latch.countDown()
    }
}

private fun featureUnavailable() = ConsumerRuntimeReadinessResult.Rejected(
    ConsumerControlPlaneFailure(
        ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE,
        "Consumer runtime readiness is unavailable",
    ),
)

private fun transportFailure() = ConsumerRuntimeReadinessResult.Rejected(
    ConsumerControlPlaneFailure(
        ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE,
        "Shared runtime transport is unavailable",
    ),
)
