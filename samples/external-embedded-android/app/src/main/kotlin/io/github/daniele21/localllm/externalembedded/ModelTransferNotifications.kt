package io.github.daniele21.localllm.externalembedded

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** A user-visible, content-free progress notification for both scheduling paths. */
internal object ModelTransferNotifications {
    private const val CHANNEL = "harnex_model_downloads"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Harnex model downloads", NotificationManager.IMPORTANCE_LOW),
        )
    }

    fun notification(context: Context, kind: ModelTransferKind, status: String, progress: Int): Notification {
        val app = context.applicationContext
        val content = PendingIntent.getActivity(
            app,
            kind.jobId,
            Intent(app, EmbeddedSmokeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getBroadcast(
            app,
            kind.jobId,
            Intent(app, ModelTransferCancelReceiver::class.java).putExtra("kind", kind.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Harnex · ${kind.label}")
            .setContentText(status.take(100))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .addAction(android.R.drawable.ic_delete, "Pause", cancel)
            .build()
    }
}
