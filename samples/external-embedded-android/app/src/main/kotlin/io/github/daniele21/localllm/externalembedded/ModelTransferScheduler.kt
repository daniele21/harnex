package io.github.daniele21.localllm.externalembedded

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/** Schedules user-visible long downloads only as a result of an Activity button press. */
internal object ModelTransferScheduler {
    fun start(context: Context, kind: ModelTransferKind): Boolean {
        val app = context.applicationContext
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = app.getSystemService(JobScheduler::class.java)
                if (scheduler.getPendingJob(kind.jobId) != null) return true
                val job = JobInfo.Builder(kind.jobId, ComponentName(app, ModelTransferJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setEstimatedNetworkBytes(kind.expectedBytes, 0L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build()
                ModelTransferState.start(app, kind)
                check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS) { "Android rejected the download job" }
            } else {
                ModelTransferState.start(app, kind)
                app.startForegroundService(
                    Intent(app, ModelTransferForegroundService::class.java)
                        .setAction(ModelTransferForegroundService.ACTION_START)
                        .putExtra(ModelTransferForegroundService.EXTRA_KIND, kind.name),
                )
            }
            true
        } catch (_: IllegalStateException) {
            ModelTransferState.update(app, kind, ModelTransferPhase.FAILED, "Unable to schedule download · retry")
            false
        } catch (_: IllegalArgumentException) {
            ModelTransferState.update(app, kind, ModelTransferPhase.FAILED, "Unsupported download request · retry")
            false
        } catch (_: SecurityException) {
            ModelTransferState.update(app, kind, ModelTransferPhase.FAILED, "Android blocked the download · check app settings")
            false
        }
    }

    fun reconcile(context: Context, kind: ModelTransferKind) {
        if (ModelTransferState.snapshot(context, kind).phase != ModelTransferPhase.RUNNING) return
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).getPendingJob(kind.jobId) != null
        } else {
            ModelTransferForegroundService.isActive()
        }
        if (!active) {
            ModelTransferState.update(
                context, kind, ModelTransferPhase.PAUSED,
                "Transfer interrupted · tap Prepare to resume the saved bytes",
            )
        }
    }

    fun cancel(context: Context, kind: ModelTransferKind) {
        val app = context.applicationContext
        ModelTransferState.requestCancel(app, kind)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            app.getSystemService(JobScheduler::class.java).cancel(kind.jobId)
        } else {
            app.startService(
                Intent(app, ModelTransferForegroundService::class.java)
                    .setAction(ModelTransferForegroundService.ACTION_CANCEL)
                    .putExtra(ModelTransferForegroundService.EXTRA_KIND, kind.name),
            )
        }
    }
}
