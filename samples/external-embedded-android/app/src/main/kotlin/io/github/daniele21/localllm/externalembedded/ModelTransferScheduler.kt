package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.content.Intent
import android.os.Build

/** Schedules user-visible long downloads only as a result of an Activity button press. */
internal object ModelTransferScheduler {
    fun start(context: Context, kind: ModelTransferKind): Boolean {
        val app = context.applicationContext
        val anotherActive = ModelTransferKind.values().any { other ->
            other != kind && (
                ModelTransferState.snapshot(app, other).phase == ModelTransferPhase.RUNNING ||
                    (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                            app.getSystemService(JobScheduler::class.java).getPendingJob(other.jobId) != null
                        )
                )
        }
        if (anotherActive) {
            ModelTransferState.update(app, kind, ModelTransferPhase.FAILED, "Finish the other download first")
            return false
        }
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
        } catch (error: SecurityException) {
            val uidt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            val uidtPermissionGranted = !uidt ||
                app.checkSelfPermission(Manifest.permission.RUN_USER_INITIATED_JOBS) == PackageManager.PERMISSION_GRANTED
            // This is a normal manifest permission, not a notification or battery setting.
            // Keep system details in debug Logcat; never log model contents or user prompts.
            Log.w(
                "HarnexTransfer",
                "Android denied ${kind.name} scheduling; user-initiated-job permission granted=$uidtPermissionGranted",
                error,
            )
            ModelTransferState.update(
                app,
                kind,
                ModelTransferPhase.FAILED,
                downloadSecurityStatus(uidt, uidtPermissionGranted),
            )
            false
        }
    }

    internal fun downloadSecurityStatus(uidt: Boolean, uidtPermissionGranted: Boolean): String = when {
        uidt && !uidtPermissionGranted -> "Installed APK lacks download permission · install updated APK"
        uidt -> "Android rejected background download · collect device diagnostics"
        else -> "Android rejected foreground download · collect device diagnostics"
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
                context,
                kind,
                ModelTransferPhase.PAUSED,
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
