package io.github.daniele21.localllm.externalembedded

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14+ system-managed user-initiated data transfer. No Activity reference.
 * Android may stop/requeue the job; partial bytes survive process death on disk.
 */
class ModelTransferJobService : JobService() {
    private val workers = Executors.newCachedThreadPool()
    private val operations = ConcurrentHashMap<Int, AtomicBoolean>()

    override fun onStartJob(params: JobParameters): Boolean {
        val kind = ModelTransferKind.values().firstOrNull { it.jobId == params.jobId } ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        ModelTransferNotifications.create(this)
        val cancelled = AtomicBoolean(false)
        if (operations.putIfAbsent(params.jobId, cancelled) != null) return true
        ModelTransferState.update(this, kind, ModelTransferPhase.RUNNING, "Downloading ${kind.label}…")
        setNotification(
            params,
            kind.jobId,
            ModelTransferNotifications.notification(this, kind, "Starting verified download…", -1),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        workers.execute {
            val outcome = ModelTransferTask.execute(this, kind, cancelled) { status, percent ->
                if (!cancelled.get()) {
                    setNotification(
                        params, kind.jobId,
                        ModelTransferNotifications.notification(this, kind, status, percent),
                        JOB_END_NOTIFICATION_POLICY_REMOVE,
                    )
                }
            }
            if (operations.remove(params.jobId, cancelled)) {
                if (!ModelTransferState.cancelRequested(this, kind)) {
                    val phase = if (outcome.success) ModelTransferPhase.COMPLETED else ModelTransferPhase.FAILED
                    ModelTransferState.update(this, kind, phase, outcome.status)
                }
                jobFinished(params, !outcome.success && outcome.retryable)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val kind = ModelTransferKind.values().firstOrNull { it.jobId == params.jobId } ?: return false
        operations.remove(params.jobId)?.set(true)
        val userCancelled = ModelTransferState.cancelRequested(this, kind)
        if (!userCancelled) {
            ModelTransferState.update(this, kind, ModelTransferPhase.PAUSED, "Android paused download · it will resume")
        }
        return !userCancelled
    }

    override fun onDestroy() {
        operations.values.forEach { it.set(true) }
        workers.shutdownNow()
        super.onDestroy()
    }
}
