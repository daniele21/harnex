package io.github.daniele21.localllm.transport.binder.client

import android.os.DeadObjectException
import android.os.RemoteException
import io.github.daniele21.localllm.contracts.ConsumerActivationId
import io.github.daniele21.localllm.contracts.ConsumerActivationRequest
import io.github.daniele21.localllm.contracts.ConsumerActivationResult
import io.github.daniele21.localllm.contracts.ConsumerAssignedUseCasesResult
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneClient
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneErrorCode
import io.github.daniele21.localllm.contracts.ConsumerControlPlaneFailure
import io.github.daniele21.localllm.contracts.ConsumerDeactivationResult
import io.github.daniele21.localllm.contracts.ConsumerPublishedPresetsResult
import io.github.daniele21.localllm.contracts.ConsumerSetupResolutionRequest
import io.github.daniele21.localllm.contracts.ConsumerSetupResolutionResult
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.transport.binder.contract.BinderProtocolV1
import io.github.daniele21.localllm.transport.binder.contract.ConsumerControlPlaneRequestParcel
import io.github.daniele21.localllm.transport.binder.contract.ConsumerControlPlaneResultParcel
import io.github.daniele21.localllm.transport.binder.contract.toConsumerControlPlaneWire
import io.github.daniele21.localllm.transport.binder.contract.toCoreActivationResult
import io.github.daniele21.localllm.transport.binder.contract.toCoreAssignedUseCasesResult
import io.github.daniele21.localllm.transport.binder.contract.toCoreDeactivationResult
import io.github.daniele21.localllm.transport.binder.contract.toCorePublishedPresetsResult
import io.github.daniele21.localllm.transport.binder.contract.toCoreSetupResolutionResult
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BinderConsumerControlPlaneAdapter(
    private val endpointProvider: () -> RegisteredSharedRuntimeEndpoint?,
    private val enabledFeaturesProvider: () -> Set<String>,
    private val endpointInvalidations: SharedRuntimeEndpointInvalidationSource? = null,
    private val blockingCallGuard: BlockingCallGuard = AndroidMainThreadBlockingCallGuard,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val correlationIds: CorrelationIdSource = CorrelationIdSource { UUID.randomUUID().toString() },
) : ConsumerControlPlaneClient {
    init {
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
    }

    override fun assignedUseCases(): ConsumerAssignedUseCasesResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return assignedTransportFailure()
        if (!controlPlaneEnabled()) return assignedFeatureUnavailable()
        val operationId = correlationIds.nextId()
        val request = ConsumerControlPlaneRequestParcel(endpoint.clientToken, operationId)
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.discoverUseCases(request, callback) }
        return when (outcome) {
            is ControlPlaneRemoteOutcome.Received -> if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                assignedTransportFailure()
            } else {
                runCatching { outcome.result.toCoreAssignedUseCasesResult() }.getOrElse { assignedTransportFailure() }
            }

            else -> assignedTransportFailure()
        }
    }

    override fun publishedPresets(useCaseId: UseCaseId): ConsumerPublishedPresetsResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return presetsTransportFailure()
        if (!controlPlaneEnabled()) return presetsFeatureUnavailable()
        val operationId = correlationIds.nextId()
        val request = ConsumerControlPlaneRequestParcel(
            clientToken = endpoint.clientToken,
            operationId = operationId,
            useCaseId = useCaseId.value,
        )
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.discoverPresets(request, callback) }
        return when (outcome) {
            is ControlPlaneRemoteOutcome.Received -> if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                presetsTransportFailure()
            } else {
                runCatching { outcome.result.toCorePublishedPresetsResult() }.getOrElse { presetsTransportFailure() }
            }

            else -> presetsTransportFailure()
        }
    }

    override fun resolveSetup(request: ConsumerSetupResolutionRequest): ConsumerSetupResolutionResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return setupTransportFailure()
        if (!setupResolutionEnabled()) return setupFeatureUnavailable()
        val operationId = correlationIds.nextId()
        val wire = request.toConsumerControlPlaneWire(endpoint.clientToken, operationId)
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.resolveSetup(wire, callback) }
        return when (outcome) {
            is ControlPlaneRemoteOutcome.Received -> if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                setupTransportFailure()
            } else {
                runCatching { outcome.result.toCoreSetupResolutionResult() }.getOrElse { setupTransportFailure() }
            }

            else -> setupTransportFailure()
        }
    }

    override fun activate(request: ConsumerActivationRequest): ConsumerActivationResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return activationTransportFailure()
        if (!controlPlaneEnabled()) return activationFeatureUnavailable()
        val operationId = correlationIds.nextId()
        val wire = request.toConsumerControlPlaneWire(endpoint.clientToken, operationId)
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.activate(wire, callback) }
        return when (outcome) {
            is ControlPlaneRemoteOutcome.Received -> if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                activationTransportFailure()
            } else {
                runCatching { outcome.result.toCoreActivationResult() }.getOrElse { activationTransportFailure() }
            }

            else -> activationTransportFailure()
        }
    }

    override fun deactivate(activationId: ConsumerActivationId): ConsumerDeactivationResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return deactivationTransportFailure()
        if (!controlPlaneEnabled()) return deactivationFeatureUnavailable()
        val operationId = correlationIds.nextId()
        val request = ConsumerControlPlaneRequestParcel(
            clientToken = endpoint.clientToken,
            operationId = operationId,
            activationId = activationId.value,
        )
        val outcome = await(endpoint) { callback -> endpoint.service.consumer.deactivate(request, callback) }
        return when (outcome) {
            is ControlPlaneRemoteOutcome.Received -> if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                deactivationTransportFailure()
            } else {
                runCatching { outcome.result.toCoreDeactivationResult(activationId) }.getOrElse { deactivationTransportFailure() }
            }

            else -> deactivationTransportFailure()
        }
    }

    private fun controlPlaneEnabled(): Boolean = BinderProtocolV1.FEATURE_CONSUMER_CONTROL_PLANE_V1 in enabledFeaturesProvider()

    private fun setupResolutionEnabled(): Boolean = BinderProtocolV1.FEATURE_CONSUMER_SETUP_RESOLUTION_V1 in enabledFeaturesProvider()

    private fun await(
        endpoint: RegisteredSharedRuntimeEndpoint,
        call: ((ConsumerControlPlaneResultParcel) -> Unit) -> Unit,
    ): ControlPlaneRemoteOutcome {
        val waiter = ControlPlaneCallbackWaiter()
        val subscription = endpointInvalidations?.addListener { epoch, _ ->
            if (epoch == endpoint.connectionEpoch) waiter.disconnect()
        }
        return try {
            call(waiter::complete)
            waiter.await(operationTimeoutMillis)
        } catch (_: DeadObjectException) {
            ControlPlaneRemoteOutcome.Disconnected
        } catch (_: RemoteException) {
            ControlPlaneRemoteOutcome.TransportFailure
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

private sealed interface ControlPlaneRemoteOutcome {
    data class Received(val result: ConsumerControlPlaneResultParcel) : ControlPlaneRemoteOutcome

    data object Timeout : ControlPlaneRemoteOutcome

    data object Disconnected : ControlPlaneRemoteOutcome

    data object TransportFailure : ControlPlaneRemoteOutcome
}

private class ControlPlaneCallbackWaiter {
    private val latch = CountDownLatch(1)
    private val outcome = AtomicReference<ControlPlaneRemoteOutcome?>(null)

    fun complete(result: ConsumerControlPlaneResultParcel) = finish(ControlPlaneRemoteOutcome.Received(result))

    fun disconnect() {
        finish(ControlPlaneRemoteOutcome.Disconnected)
    }

    fun await(timeoutMillis: Long): ControlPlaneRemoteOutcome = if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        requireNotNull(outcome.get())
    } else {
        ControlPlaneRemoteOutcome.Timeout
    }

    private fun finish(value: ControlPlaneRemoteOutcome) {
        if (outcome.compareAndSet(null, value)) latch.countDown()
    }
}

private fun controlPlaneFailure(code: ConsumerControlPlaneErrorCode, message: String) = ConsumerControlPlaneFailure(code, message)

private fun assignedFeatureUnavailable() = ConsumerAssignedUseCasesResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE, "Consumer control plane is unavailable"),
)

private fun assignedTransportFailure() = ConsumerAssignedUseCasesResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE, "Shared runtime transport is unavailable"),
)

private fun presetsFeatureUnavailable() = ConsumerPublishedPresetsResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE, "Consumer control plane is unavailable"),
)

private fun presetsTransportFailure() = ConsumerPublishedPresetsResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE, "Shared runtime transport is unavailable"),
)

private fun activationFeatureUnavailable() = ConsumerActivationResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE, "Consumer control plane is unavailable"),
)

private fun activationTransportFailure() = ConsumerActivationResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE, "Shared runtime transport is unavailable"),
)

private fun deactivationFeatureUnavailable() = ConsumerDeactivationResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.FEATURE_UNAVAILABLE, "Consumer control plane is unavailable"),
)

private fun deactivationTransportFailure() = ConsumerDeactivationResult.Rejected(
    controlPlaneFailure(ConsumerControlPlaneErrorCode.TRANSPORT_FAILURE, "Shared runtime transport is unavailable"),
)
