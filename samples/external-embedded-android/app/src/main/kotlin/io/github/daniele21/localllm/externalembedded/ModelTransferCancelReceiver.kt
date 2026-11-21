package io.github.daniele21.localllm.externalembedded

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit user action; the system never interprets screen lock as cancellation. */
class ModelTransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = ModelTransferKind.values().firstOrNull { it.name == intent.getStringExtra("kind") } ?: return
        ModelTransferScheduler.cancel(context, kind)
    }
}
