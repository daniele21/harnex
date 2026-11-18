package io.github.daniele21.localllm.transport.binder.client

import android.os.DeadObjectException
import android.os.RemoteException
import io.github.daniele21.localllm.contracts.ConsumerCapabilityErrorCode
import io.github.daniele21.localllm.contracts.ConsumerCapabilityResult
import io.github.daniele21.localllm.contracts.ConsumerErrorCode
import io.github.daniele21.localllm.contracts.ConsumerFailure
import io.github.daniele21.localllm.contracts.ConsumerPrepareRequest
import io.github.daniele21.localllm.contracts.ConsumerPrepareResult
import io.github.daniele21.localllm.contracts.ConsumerPreparedId
import io.github.daniele21.localllm.contracts.ConsumerSessionResult
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.transport.binder.contract.CloseSessionRequestParcel
import io.github.daniele21.localllm.transport.binder.contract.ConsumerRequestParcel
import io.github.daniele21.localllm.transport.binder.contract.ConsumerResultParcel
import io.github.daniele21.localllm.transport.binder.contract.toConsumerWire
import io.github.daniele21.localllm.transport.binder.contract.toCoreCapabilityResult
import io.github.daniele21.localllm.transport.binder.contract.toCorePrepareResult
import io.github.daniele21.localllm.transport.binder.contract.toCoreSessionResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class BinderConsumerLifecycleAdapter(
    private val endpointProvider: () -> RegisteredSharedRuntimeEndpoint?,
    private val endpointInvalidations: SharedRuntimeEndpointInvalidationSource? = null,
    private val blockingCallGuard: BlockingCallGuard = AndroidMainThreadBlockingCallGuard,
    private val operationTimeoutMillis: Long = DEFAULT_OPERATION_TIMEOUT_MILLIS,
    private val correlationIds: CorrelationIdSource = CorrelationIdSource { UUID.randomUUID().toString() },
) : AutoCloseable {
    private val sessions = ConsumerBinderSessionRegistry()
    private val invalidationSubscription = endpointInvalidations?.addListener { epoch, _ -> sessions.invalidate(epoch) }

    init {
        require(operationTimeoutMillis > 0) { "operationTimeoutMillis must be positive" }
    }

    fun capabilities(useCaseId: UseCaseId): ConsumerCapabilityResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider()
            ?: return ConsumerCapabilityResult.Rejected(
                ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE,
                "Shared runtime is not connected",
            )
        val operationId = correlationIds.nextId()
        val request = ConsumerRequestParcel(
            clientToken = endpoint.clientToken,
            operationId = operationId,
            useCaseId = useCaseId.value,
        )
        return when (val outcome = await(endpoint) { callback -> endpoint.service.consumer.capabilities(request, callback) }) {
            is ConsumerRemoteOutcome.Received -> {
                if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                    capabilityTransportFailure()
                } else {
                    runCatching { outcome.result.toCoreCapabilityResult() }.getOrElse { capabilityTransportFailure() }
                }
            }

            else -> capabilityTransportFailure()
        }
    }

    fun prepare(request: ConsumerPrepareRequest): ConsumerPrepareResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return prepareTransportFailure()
        val operationId = correlationIds.nextId()
        val wire = ConsumerRequestParcel(
            clientToken = endpoint.clientToken,
            operationId = operationId,
            useCaseId = request.useCaseId.value,
            selection = request.selection.toConsumerWire(),
        )
        return when (val outcome = await(endpoint) { callback -> endpoint.service.consumer.prepare(wire, callback) }) {
            is ConsumerRemoteOutcome.Received -> {
                if (outcome.result.operationId != operationId || !isCurrent(endpoint)) {
                    prepareTransportFailure()
                } else {
                    runCatching { outcome.result.toCorePrepareResult() }.getOrElse { prepareTransportFailure() }
                }
            }

            else -> prepareTransportFailure()
        }
    }

    fun createSession(preparedId: ConsumerPreparedId): ConsumerSessionResult {
        blockingCallGuard.requireAllowed()
        val endpoint = endpointProvider() ?: return sessionTransportFailure()
        val operationId = correlationIds.nextId()
        val externalSessionId = correlationIds.nextId()
        val request = ConsumerRequestParcel(
            clientToken = endpoint.clientToken,
            operationId = operationId,
            preparedId = preparedId.value,
            externalSessionId = externalSessionId,
        )
        return when (val outcome = await(endpoint) { callback -> endpoint.service.consumer.openSession(request, callback) }) {
            is ConsumerRemoteOutcome.Received -> mapSession(endpoint, operationId, externalSessionId, outcome.result)

            ConsumerRemoteOutcome.Timeout -> {
                closeRemoteSession(endpoint, externalSessionId)
                sessionTransportFailure()
            }

            else -> sessionTransportFailure()
        }
    }

    fun closeSession(sessionId: SessionId) {
        val endpoint = sessions.takeForClose(sessionId.value) ?: return
        closeRemoteSession(endpoint, sessionId.value)
    }

    override fun close() {
        sessions.drain().forEach { (id, endpoint) -> closeRemoteSession(endpoint, id) }
        invalidationSubscription?.close()
        sessions.clearClosed()
    }

    private fun mapSession(
        endpoint: RegisteredSharedRuntimeEndpoint,
        operationId: String,
        externalSessionId: String,
        result: ConsumerResultParcel,
    ): ConsumerSessionResult {
        if (!isCurrent(endpoint) || result.operationId != operationId) return sessionTransportFailure()
        val mapped = runCatching { result.toCoreSessionResult() }.getOrElse { return sessionTransportFailure() }
        if (mapped is ConsumerSessionResult.Created) {
            if (mapped.sessionId.value != externalSessionId) return sessionTransportFailure()
            sessions.register(externalSessionId, endpoint)
        }
        return mapped
    }

    private fun closeRemoteSession(endpoint: RegisteredSharedRuntimeEndpoint, externalSessionId: String) {
        if (!isCurrent(endpoint)) return
        try {
            endpoint.service.consumer.closeSession(
                CloseSessionRequestParcel(endpoint.clientToken, externalSessionId),
            )
        } catch (_: RemoteException) {
            // Best effort; host connection cleanup owns final resource release.
        }
    }

    private fun await(endpoint: RegisteredSharedRuntimeEndpoint, call: ((ConsumerResultParcel) -> Unit) -> Unit): ConsumerRemoteOutcome {
        val waiter = ConsumerCallbackWaiter()
        val subscription = endpointInvalidations?.addListener { epoch, _ ->
            if (epoch == endpoint.connectionEpoch) waiter.disconnect()
        }
        return try {
            call(waiter::complete)
            waiter.await(operationTimeoutMillis)
        } catch (_: DeadObjectException) {
            ConsumerRemoteOutcome.Disconnected
        } catch (_: RemoteException) {
            ConsumerRemoteOutcome.TransportFailure
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

private sealed interface ConsumerRemoteOutcome {
    data class Received(val result: ConsumerResultParcel) : ConsumerRemoteOutcome
    data object Timeout : ConsumerRemoteOutcome
    data object Disconnected : ConsumerRemoteOutcome
    data object TransportFailure : ConsumerRemoteOutcome
}

private class ConsumerCallbackWaiter {
    private val latch = CountDownLatch(1)
    private val outcome = AtomicReference<ConsumerRemoteOutcome?>(null)

    fun complete(result: ConsumerResultParcel) = finish(ConsumerRemoteOutcome.Received(result))

    fun disconnect() {
        finish(ConsumerRemoteOutcome.Disconnected)
    }

    fun await(timeoutMillis: Long): ConsumerRemoteOutcome = if (latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
        requireNotNull(outcome.get())
    } else {
        ConsumerRemoteOutcome.Timeout
    }

    private fun finish(value: ConsumerRemoteOutcome) {
        if (outcome.compareAndSet(null, value)) latch.countDown()
    }
}

private class ConsumerBinderSessionRegistry {
    private val closed = ConcurrentHashMap.newKeySet<String>()
    private val open = ConcurrentHashMap<String, RegisteredSharedRuntimeEndpoint>()

    fun register(sessionId: String, endpoint: RegisteredSharedRuntimeEndpoint) {
        closed.remove(sessionId)
        open[sessionId] = endpoint
    }

    fun takeForClose(sessionId: String): RegisteredSharedRuntimeEndpoint? {
        if (!closed.add(sessionId)) return null
        return open.remove(sessionId)
    }

    fun invalidate(connectionEpoch: Long) {
        open.forEach { (sessionId, endpoint) ->
            if (endpoint.connectionEpoch == connectionEpoch) open.remove(sessionId, endpoint)
        }
    }

    fun drain(): List<Pair<String, RegisteredSharedRuntimeEndpoint>> {
        val snapshot = open.entries.map { it.key to it.value }
        snapshot.forEach { (id, endpoint) -> if (open.remove(id, endpoint)) closed.add(id) }
        return snapshot
    }

    fun clearClosed() = closed.clear()
}

private fun capabilityTransportFailure() = ConsumerCapabilityResult.Rejected(
    ConsumerCapabilityErrorCode.CAPABILITY_INCOMPATIBLE,
    "Shared runtime transport is unavailable",
)

private fun prepareTransportFailure() =
    ConsumerPrepareResult.Rejected(ConsumerFailure(ConsumerErrorCode.RUNTIME_FAILURE, "Shared runtime transport is unavailable"))

private fun sessionTransportFailure() =
    ConsumerSessionResult.Rejected(ConsumerFailure(ConsumerErrorCode.RUNTIME_FAILURE, "Shared runtime transport is unavailable"))
