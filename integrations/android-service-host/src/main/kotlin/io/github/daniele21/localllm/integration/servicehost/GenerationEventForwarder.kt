package io.github.daniele21.localllm.integration.servicehost

import io.github.daniele21.localllm.contracts.GenerationEvent
import io.github.daniele21.localllm.transport.binder.contract.WireErrorCodes
import io.github.daniele21.localllm.transport.binder.contract.chunkDelta
import io.github.daniele21.localllm.transport.binder.contract.toWire
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class GenerationEventForwarder(
    private val externalRequestId: String,
    private val callback: HostEventCallback,
    private val dispatcher: HostCallbackDispatcher,
    private val onTerminal: () -> Unit,
    private val onCallbackFailure: () -> Unit,
    private val onBackpressure: () -> Unit,
) {
    private val nextSequence = AtomicLong(0)
    private val terminalQueued = AtomicBoolean(false)
    private val deliveryFailed = AtomicBoolean(false)
    private val cleanupTriggered = AtomicBoolean(false)
    private val pendingNonTerminal = AtomicInteger(0)

    val callbackFailed: Boolean
        get() = deliveryFailed.get()

    fun onEvent(event: GenerationEvent) {
        // Embedded-only image stages have no Binder wire representation.
        if (event is GenerationEvent.VisionProgress) return
        if (deliveryFailed.get() || terminalQueued.get()) return
        val events =
            if (event is GenerationEvent.TextDelta) {
                chunkDelta(event.text).map { chunk -> event.copy(text = chunk) }
            } else {
                listOf(event)
            }
        events.forEach(::queue)
    }

    private fun queue(event: GenerationEvent) {
        if (deliveryFailed.get() || terminalQueued.get()) return
        if (event is GenerationEvent.Completed || event is GenerationEvent.Failed) {
            queueTerminal(event)
        } else {
            queueNonTerminal(event)
        }
    }

    private fun queueNonTerminal(event: GenerationEvent) {
        if (pendingNonTerminal.incrementAndGet() > MAX_PENDING_NON_TERMINAL_CALLBACKS) {
            pendingNonTerminal.decrementAndGet()
            failBackpressure()
            return
        }
        val accepted =
            dispatcher.dispatch {
                pendingNonTerminal.decrementAndGet()
                if (!deliveryFailed.get()) deliver(event, terminal = false)
            }
        if (!accepted) {
            pendingNonTerminal.decrementAndGet()
            failBackpressure()
        }
    }

    private fun queueTerminal(event: GenerationEvent) {
        if (!terminalQueued.compareAndSet(false, true)) return
        if (!dispatcher.dispatch { deliver(event, terminal = true) }) {
            failDelivery()
        }
    }

    private fun deliver(event: GenerationEvent, terminal: Boolean) {
        val wireEvent = event.toWire(externalRequestId, nextSequence.getAndIncrement())
        try {
            callback.onEvent(wireEvent)
            if (terminal) onTerminal()
        } catch (_: RuntimeException) {
            failDelivery()
        }
    }

    private fun failBackpressure() {
        if (!deliveryFailed.compareAndSet(false, true)) return
        terminalQueued.set(true)
        onBackpressure()
        val accepted =
            dispatcher.dispatch {
                val failure =
                    generationFailure(
                        externalRequestId = externalRequestId,
                        error = wireError(WireErrorCodes.CLIENT_BACKPRESSURE),
                        sequence = nextSequence.getAndIncrement(),
                    )
                try {
                    callback.onEvent(failure)
                    onTerminal()
                } catch (_: RuntimeException) {
                    triggerFailureCleanup()
                }
            }
        if (!accepted) triggerFailureCleanup()
    }

    private fun failDelivery() {
        deliveryFailed.set(true)
        terminalQueued.set(true)
        triggerFailureCleanup()
    }

    private fun triggerFailureCleanup() {
        if (cleanupTriggered.compareAndSet(false, true)) onCallbackFailure()
    }

    private companion object {
        const val MAX_PENDING_NON_TERMINAL_CALLBACKS = 15
    }
}
