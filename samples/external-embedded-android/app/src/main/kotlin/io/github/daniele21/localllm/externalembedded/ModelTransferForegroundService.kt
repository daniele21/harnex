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
    @Volatile
    private var currentKind: ModelTransferKind? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent ?: return START_NOT_STICKY
        val kind = ModelTransferKind.values().firstOrNull {
            it.name == request.getStringExtra(EXTRA_KIND)
        } ?: return START_NOT_STICKY
        if (request.action == ACTION_CANCEL) {
            // A stale notification for another model cannot cancel an unrelated download.
            if (currentKind == kind) cancelled.set(true)
            if (!running.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (request.action != ACTION_START) return START_NOT_STICKY
        if (!running.compareAndSet(false, true)) {
            if (currentKind != kind) {
                ModelTransferState.update(
                    this, kind, ModelTransferPhase.FAILED,
                    "Another model download is already running · finish it first",
                )
            }
            return START_NOT_STICKY
        }
        currentKind = kind
        cancelled.set(false)
        active = true
        ModelTransferState.update(this, kind, ModelTransferPhase.RUNNING, "Downloading ${kind.label}…")
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
                val result = try {
                    ModelTransferTask.execute(this, kind, cancelled) { status, percent ->
                        if (!cancelled.get()) {
                            getSystemService(android.app.NotificationManager::class.java).notify(
                                kind.jobId,
                                ModelTransferNotifications.notification(this, kind, status, percent),
                            )
                        }
                    }
                } catch (error: RuntimeException) {
                    ModelTransferOutcome(false, false, "Download failed (${error.javaClass.simpleName}) · retry")
                }
                if (!ModelTransferState.cancelRequested(this, kind)) {
                    ModelTransferState.update(
                        this,
                        kind,
                        if (result.success) ModelTransferPhase.COMPLETED else ModelTransferPhase.FAILED,
                        result.status,
                    )
                }
            } finally {
                releaseWakeLock()
                currentKind = null
                running.set(false)
                active = false
                stopSelf(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        cancelled.set(true)
        releaseWakeLock()
        worker.shutdownNow()
        currentKind = null
        active = false
        super.onDestroy()
    }

    @Synchronized
    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
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
