package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build

/**
 * Consumer-owned UI adapter. Android jobs/services own downloads; this class only
 * observes content-free durable status and prepares the model after verified completion.
 */
internal class ModelTransferUiController(
    private val activity: Activity,
    private val screen: EmbeddedChatScreen,
    private val chat: EmbeddedChatController,
    private val chatPhase: () -> ChatPhase,
) {
    private var handledModelRevision = 0L
    private var handledVisionRevision = 0L
    private var permissionPendingKind: ModelTransferKind? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        activity.runOnUiThread { if (!activity.isDestroyed && !activity.isFinishing) render() }
    }

    fun start(kind: ModelTransferKind) {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val promptPreferences = activity.getPreferences(Context.MODE_PRIVATE)
        if (needsNotificationPermission && !promptPreferences.getBoolean("asked_download_notifications", false)) {
            promptPreferences.edit().putBoolean("asked_download_notifications", true).apply()
            permissionPendingKind = kind
            activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        enqueue(kind)
    }

    fun onNotificationPermissionResult() {
        val kind = permissionPendingKind ?: return
        permissionPendingKind = null
        enqueue(kind)
    }

    fun cancelIfRunning(): Boolean {
        val kind = ModelTransferKind.values().firstOrNull {
            ModelTransferState.snapshot(activity, it).phase == ModelTransferPhase.RUNNING
        } ?: return false
        ModelTransferScheduler.cancel(activity, kind)
        render()
        return true
    }

    fun onStart() {
        ModelTransferKind.values().forEach { ModelTransferScheduler.reconcile(activity, it) }
        ModelTransferState.preferences(activity).registerOnSharedPreferenceChangeListener(listener)
        render()
    }

    fun onStop() {
        ModelTransferState.preferences(activity).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun render() {
        val model = ModelTransferState.snapshot(activity, ModelTransferKind.MODEL)
        when {
            model.phase == ModelTransferPhase.RUNNING && chatPhase() == ChatPhase.NEEDS_MODEL -> {
                screen.updateControls(ChatPhase.PREPARING)
                screen.setStatus(model.status)
            }
            model.phase == ModelTransferPhase.COMPLETED && chatPhase() == ChatPhase.NEEDS_MODEL &&
                model.revision != handledModelRevision -> {
                handledModelRevision = model.revision
                chat.prepare()
            }
            chatPhase() == ChatPhase.NEEDS_MODEL &&
                model.phase in setOf(ModelTransferPhase.FAILED, ModelTransferPhase.PAUSED, ModelTransferPhase.CANCELLED) -> {
                screen.updateControls(ChatPhase.NEEDS_MODEL)
                screen.setStatus(model.status)
            }
        }

        if (chatPhase() != ChatPhase.READY) return
        val vision = ModelTransferState.snapshot(activity, ModelTransferKind.VISION)
        when (vision.phase) {
            ModelTransferPhase.RUNNING -> {
                screen.vision.setReady(ready = false, preparing = true)
                screen.vision.setStatus(vision.status)
            }
            ModelTransferPhase.COMPLETED -> if (vision.revision != handledVisionRevision) {
                handledVisionRevision = vision.revision
                chat.prepareVision()
            }
            ModelTransferPhase.FAILED, ModelTransferPhase.PAUSED, ModelTransferPhase.CANCELLED -> {
                screen.vision.setReady(ready = false, preparing = false)
                screen.vision.setStatus(vision.status)
            }
            ModelTransferPhase.IDLE -> Unit
        }
    }

    private fun enqueue(kind: ModelTransferKind) {
        ModelTransferScheduler.start(activity, kind)
        render()
    }

    companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 482
    }
}
