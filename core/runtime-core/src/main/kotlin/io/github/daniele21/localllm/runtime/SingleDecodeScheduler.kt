package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.RequestId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class DecodePriority(val rank: Int) {
    USER_INTERACTIVE(0),
    FOREGROUND(1),
    BACKGROUND(2),
    MAINTENANCE(3),
}

data class DecodeSchedulerSnapshot(val activeRequest: RequestId?, val queuedRequests: Int, val closed: Boolean)

data class DecodeSubmission(val queuePosition: Int, val handle: DecodeTaskHandle)

interface DecodeTaskHandle {
    val requestId: RequestId
    fun cancel(): Boolean
}

class DecodeQueueCapacityExceededException(val capacity: Int) :
    IllegalStateException(
        "Decode scheduler capacity of $capacity outstanding request(s) is exhausted",
    )

class SingleDecodeScheduler(
    threadFactory: ThreadFactory = ThreadFactory { runnable ->
        Thread(runnable, "local-llm-decode").apply { isDaemon = true }
    },
    private val maxOutstandingRequests: Int = DEFAULT_MAX_OUTSTANDING_REQUESTS,
    private val priorityFairnessWindow: Long = DEFAULT_PRIORITY_FAIRNESS_WINDOW,
) : AutoCloseable {
    init {
        require(maxOutstandingRequests > 0) { "Maximum outstanding decode requests must be positive" }
        require(priorityFairnessWindow > 0) { "Priority fairness window must be positive" }
    }

    private val queue = PriorityBlockingQueue<ScheduledWork>()
    private val works = ConcurrentHashMap<RequestId, ScheduledWork>()
    private val capacity = Semaphore(maxOutstandingRequests, true)
    private val sequence = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val activeRequest = AtomicReference<RequestId?>(null)
    private val worker = threadFactory.newThread(::runLoop).apply { start() }

    fun submit(
        requestId: RequestId,
        priority: DecodePriority,
        task: () -> Unit,
        onQueuedCancellation: () -> Unit,
        onRunningCancellation: () -> Unit,
        onFinished: () -> Unit = {},
        onQueued: (position: Int) -> Unit = {},
    ): DecodeSubmission {
        check(!closed.get()) { "Decode scheduler is closed" }
        if (!capacity.tryAcquire()) {
            throw DecodeQueueCapacityExceededException(maxOutstandingRequests)
        }
        val work = ScheduledWork(
            requestId = requestId,
            priority = priority,
            sequence = sequence.getAndIncrement(),
            fairnessWindow = priorityFairnessWindow,
            task = task,
            onQueuedCancellation = onQueuedCancellation,
            onRunningCancellation = onRunningCancellation,
            onFinished = onFinished,
        )
        return try {
            check(works.putIfAbsent(requestId, work) == null) {
                "A decode request with ID ${requestId.value} is already scheduled"
            }

            val position = queue.count { candidate -> candidate.compareTo(work) <= 0 } + 1
            runCatching { onQueued(position) }
                .onFailure { works.remove(requestId, work) }
                .getOrThrow()
            queue.put(work)
            DecodeSubmission(
                queuePosition = position,
                handle = SchedulerHandle(requestId, this),
            )
        } catch (error: Throwable) {
            works.remove(requestId, work)
            work.releaseCapacity(capacity)
            throw error
        }
    }

    fun cancel(requestId: RequestId): Boolean {
        val work = works[requestId] ?: return false
        if (!work.cancelled.compareAndSet(false, true)) {
            return false
        }

        when {
            work.started.get() -> work.onRunningCancellation()

            queue.remove(work) -> {
                works.remove(requestId, work)
                try {
                    work.notifyQueuedCancellation()
                } finally {
                    work.releaseCapacity(capacity)
                    work.notifyFinished()
                }
            }

            work.started.get() -> work.onRunningCancellation()
        }
        return true
    }

    fun cancelAll(): Int = works.keys.count { requestId -> cancel(requestId) }

    /** Prevent synchronous preflight reentry from a generation callback on this worker. */
    fun isWorkerThread(): Boolean = Thread.currentThread() === worker

    fun snapshot(): DecodeSchedulerSnapshot = DecodeSchedulerSnapshot(
        activeRequest = activeRequest.get(),
        queuedRequests = queue.count { !it.cancelled.get() },
        closed = closed.get(),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        queue.forEach { work -> cancel(work.requestId) }
        worker.interrupt()
    }

    private fun runLoop() {
        while (!closed.get()) {
            takeNextWork()?.let(::execute)
        }
    }

    private fun takeNextWork(): ScheduledWork? = try {
        queue.take()
    } catch (_: InterruptedException) {
        null
    }

    private fun execute(work: ScheduledWork) {
        if (work.cancelled.get()) {
            works.remove(work.requestId, work)
            try {
                work.notifyQueuedCancellation()
            } finally {
                work.releaseCapacity(capacity)
                work.notifyFinished()
            }
            return
        }
        if (!work.started.compareAndSet(false, true)) {
            work.releaseCapacity(capacity)
            work.notifyFinished()
            return
        }

        activeRequest.set(work.requestId)
        try {
            if (work.cancelled.get()) {
                work.onRunningCancellation()
            } else {
                work.task()
            }
        } finally {
            works.remove(work.requestId, work)
            work.releaseCapacity(capacity)
            activeRequest.set(null)
            work.notifyFinished()
        }
    }

    private class ScheduledWork(
        val requestId: RequestId,
        val priority: DecodePriority,
        val sequence: Long,
        fairnessWindow: Long,
        val task: () -> Unit,
        val onQueuedCancellation: () -> Unit,
        val onRunningCancellation: () -> Unit,
        val onFinished: () -> Unit,
    ) : Comparable<ScheduledWork> {
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        private val admissionWindow = sequence / fairnessWindow
        private val queuedCancellationNotified = AtomicBoolean(false)
        private val capacityReleased = AtomicBoolean(false)
        private val finishedNotified = AtomicBoolean(false)

        override fun compareTo(other: ScheduledWork): Int {
            val windowComparison = admissionWindow.compareTo(other.admissionWindow)
            if (windowComparison != 0) return windowComparison
            val priorityComparison = priority.rank.compareTo(other.priority.rank)
            return if (priorityComparison != 0) priorityComparison else sequence.compareTo(other.sequence)
        }

        fun notifyQueuedCancellation() {
            if (queuedCancellationNotified.compareAndSet(false, true)) {
                onQueuedCancellation()
            }
        }

        fun releaseCapacity(capacity: Semaphore) {
            if (capacityReleased.compareAndSet(false, true)) {
                capacity.release()
            }
        }

        fun notifyFinished() {
            if (finishedNotified.compareAndSet(false, true)) {
                runCatching { onFinished() }
            }
        }
    }

    private class SchedulerHandle(override val requestId: RequestId, private val scheduler: SingleDecodeScheduler) : DecodeTaskHandle {
        override fun cancel(): Boolean = scheduler.cancel(requestId)
    }

    private companion object {
        const val DEFAULT_MAX_OUTSTANDING_REQUESTS = 64
        const val DEFAULT_PRIORITY_FAIRNESS_WINDOW = 8L
    }
}
