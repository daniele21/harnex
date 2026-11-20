package io.github.daniele21.localllm.externalembedded

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Android 8–13 user-visible foreground transfer fallback. */
class ModelTransferForegroundService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val kind = ModelTransferKind.values().firstOrNull {
            it.name == intent?.getStringExtra(EXTRA_KIND)
        } ?: return START_NOT_STICKY
        if (intent.action == ACTION_CANCEL) {
            cancelled.set(true)
            if (!running.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START || !running.compareAndSet(false, true)) return START_REDELIVER_INTENT
        cancelled.set(false)
        active = true
        ModelTransferNotifications.create(this)
        startForeground(
            kind.jobId,
            ModelTransferNotifications.notification(this, kind, "Starting verified download…", -1),
        )
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Harnex:ModelTransfer").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_LIMIT_MS)
        }
        worker.execute {
            try {
                val result = ModelTransferTask.execute(this, kind, cancelled) { status, percent ->
                    if (!cancelled.get()) {
                        getSystemService(android.app.NotificationManager::class.java).notify(
                            kind.jobId,
                            ModelTransferNotifications.notification(this, kind, status, percent),
                        )
                    }
                }
                if (!ModelTransferState.cancelRequested(this, kind)) {
                    ModelTransferState.update(
                        this, kind,
                        if (result.success) ModelTransferPhase.COMPLETED else ModelTransferPhase.FAILED,
                        result.status,
                    )
                }
            } finally {
                wakeLock?.takeIf { it.isHeld }?.release()
                running.set(false)
                active = false
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cancelled.set(true)
        wakeLock?.takeIf { it.isHeld }?.release()
        worker.shutdownNow()
        active = false
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var active = false

        fun isActive(): Boolean = active

        const val ACTION_START = "io.github.daniele21.localllm.externalembedded.START_MODEL_DOWNLOAD"
        const val ACTION_CANCEL = "io.github.daniele21.localllm.externalembedded.CANCEL_MODEL_DOWNLOAD"
        const val EXTRA_KIND = "kind"
        private const val WAKE_LOCK_LIMIT_MS = 60L * 60L * 1_000L
    }
}
