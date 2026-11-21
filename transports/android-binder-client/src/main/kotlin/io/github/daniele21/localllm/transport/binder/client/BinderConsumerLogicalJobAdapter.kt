package io.github.daniele21.localllm.transport.binder.client

import android.os.DeadObjectException
import android.os.RemoteException
import io.github.daniele21.localllm.contracts.ConsumerErrorCode
import io.github.daniele21.localllm.contracts.ConsumerFailure
import io.github.daniele21.localllm.contracts.ConsumerInferenceJobId
import io.github.daniele21.localllm.contracts.ConsumerInferenceJobResponse
import io.github.daniele21.localllm.contracts.ConsumerLogicalJobClient
import io.github.daniele21.localllm.contracts.ConsumerLogicalJobSubmitRequest
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.transport.binder.contract.BinderProtocolV1
import io.github.daniele21.localllm.transport.binder.contract.ConsumerLogicalJobResultParcel
import io.github.daniele21.localllm.transport.binder.contract.consumerLogicalJobQueryWire
import io.github.daniele21.localllm.transport.binder.contract.toConsumerLogicalJobWire
import io.github.daniele21.localllm.transport.binder.contract.toCoreLogicalJobResponse
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BinderConsumerLogicalJobAdapter(
    private val endpointProvider: () -> RegisteredSharedRuntimeEndpoint?,
    private val enabledFeaturesProvider: () -> Set<String>,
    private val endpointInvalidations: SharedRuntimeEndpointInvalidationSource? = null,
    private val blockingCallGuard: BlockingCallGuard = AndroidMainThreadBlockingCallGuard,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val correlationIds: CorrelationIdSource = CorrelationIdSource { UUID.randomUUID().toString() },
) : ConsumerLogicalJobClient {
    init {
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
    }

    override fun submitLogicalGeneration(request: ConsumerLogicalJobSubmitRequest): ConsumerInferenceJobResponse {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return transportFailure()
        if (!logicalJobsEnabled()) return featureUnavailable()
        val operationId = correlationIds.nextId()
        val wire = request.toConsumerLogicalJobWire(endpoint.clientToken, operationId)
        return awaitResponse(endpoint, operationId) { callback ->
            endpoint.service.consumer.submitLogicalGeneration(wire, callback)
        }
    }

    override fun logicalJob(jobId: ConsumerInferenceJobId, useCaseId: UseCaseId): ConsumerInferenceJobResponse {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return transportFailure()
        if (!logicalJobsEnabled()) return featureUnavailable()
        val operationId = correlationIds.nextId()
        val wire = consumerLogicalJobQueryWire(endpoint.clientToken, operationId, jobId, useCaseId)
        return awaitResponse(endpoint, operationId) { callback ->
            endpoint.service.consumer.logicalJobStatus(wire, callback)
        }
    }

    override fun logicalJobResult(jobId: ConsumerInferenceJobId, useCaseId: UseCaseId): ConsumerInferenceJobResponse {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return transportFailure()
        if (!logicalJobsEnabled()) return featureUnavailable()
        val operationId = correlationIds.nextId()
        val wire = consumerLogicalJobQueryWire(endpoint.clientToken, operationId, jobId, useCaseId)
        return awaitResponse(endpoint, operationId) { callback ->
            endpoint.service.consumer.logicalJobResult(wire, callback)
        }
    }

    override fun cancelLogicalJob(jobId: ConsumerInferenceJobId, useCaseId: UseCaseId) {
        val endpoint = endpointProvider() ?: return
        if (!logicalJobsEnabled()) return
        val wire = consumerLogicalJobQueryWire(endpoint.clientToken, correlationIds.nextId(), jobId, useCaseId)
        runCatching { endpoint.service.consumer.cancelLogicalJob(wire) }
    }

    private fun awaitResponse(
        endpoint: RegisteredSharedRuntimeEndpoint,
        operationId: String,
        call: ((ConsumerLogicalJobResultParcel) -> Unit) -> Unit,
    ): ConsumerInferenceJobResponse {
        val outcome = await(endpoint, call)
        return when (outcome) {
            is LogicalJobRemoteOutcome.Received ->
                if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                    transportFailure()
                } else {
                    runCatching { outcome.result.toCoreLogicalJobResponse() }.getOrElse { transportFailure() }
                }

            else -> transportFailure()
        }
    }

    private fun await(
        endpoint: RegisteredSharedRuntimeEndpoint,
        call: ((ConsumerLogicalJobResultParcel) -> Unit) -> Unit,
    ): LogicalJobRemoteOutcome {
        val waiter = LogicalJobCallbackWaiter()
        val subscription = endpointInvalidations?.addListener { epoch, _ ->
            if (epoch == endpoint.connectionEpoch) waiter.disconnect()
        }
        return try {
            if (!isCurrent(endpoint)) {
                LogicalJobRemoteOutcome.Disconnected
            } else {
                call(waiter::complete)
                waiter.await(operationTimeoutMillis)
            }
        } catch (_: DeadObjectException) {
            LogicalJobRemoteOutcome.Disconnected
        } catch (_: RemoteException) {
            LogicalJobRemoteOutcome.TransportFailure
        } finally {
            subscription?.close()
        }
    }

    private fun logicalJobsEnabled(): Boolean = BinderProtocolV1.FEATURE_CONSUMER_LOGICAL_JOBS_V1 in enabledFeaturesProvider()

    private fun isCurrent(endpoint: RegisteredSharedRuntimeEndpoint): Boolean =
        endpointProvider()?.connectionEpoch == endpoint.connectionEpoch

    private companion object {
        const val DEFAULT_OPERATION_TIMEOUT_MILLIS = 120_000L
    }
}

private sealed interface LogicalJobRemoteOutcome {
    data class Received(val result: ConsumerLogicalJobResultParcel) : LogicalJobRemoteOutcome

    data object Timeout : LogicalJobRemoteOutcome

    data object Disconnected : LogicalJobRemoteOutcome

    data object TransportFailure : LogicalJobRemoteOutcome
}

private class LogicalJobCallbackWaiter {
    private val latch = CountDownLatch(1)
    private val outcome = AtomicReference<LogicalJobRemoteOutcome?>(null)

    fun complete(result: ConsumerLogicalJobResultParcel) = finish(LogicalJobRemoteOutcome.Received(result))

    fun disconnect() {
        finish(LogicalJobRemoteOutcome.Disconnected)
    }

    fun await(timeoutMillis: Long): LogicalJobRemoteOutcome = if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        requireNotNull(outcome.get())
    } else {
        LogicalJobRemoteOutcome.Timeout
    }

    private fun finish(value: LogicalJobRemoteOutcome) {
        if (outcome.compareAndSet(null, value)) latch.countDown()
    }
}

private fun featureUnavailable() = ConsumerInferenceJobResponse.Rejected(
    ConsumerFailure(
        ConsumerErrorCode.CAPABILITY_INCOMPATIBLE,
        "Shared runtime does not support durable logical jobs",
    ),
)

private fun transportFailure() = ConsumerInferenceJobResponse.Rejected(
    ConsumerFailure(
        ConsumerErrorCode.RUNTIME_FAILURE,
        "Shared runtime transport is unavailable",
    ),
)
