package io.github.daniele21.localllm.externalembedded

import android.content.Context
import android.content.SharedPreferences

/** Only an immutable reviewed artifact identity can be scheduled for transfer. */
internal enum class ModelTransferKind(val jobId: Int, val expectedBytes: Long, val label: String) {
    MODEL(4_801, 1_280_835_840L, "Qwen3.5 2B"),
    VISION(4_802, 668_227_264L, "Qwen3.5 vision projector"),
}

internal enum class ModelTransferPhase {
    IDLE, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED,
}

internal data class ModelTransferSnapshot(
    val phase: ModelTransferPhase,
    val status: String,
    val revision: Long,
    val cancelRequested: Boolean,
)

/**
 * Durable, content-free transfer status. A recreated Activity observes the same progress
 * without owning the JobService/foreground service, their threads or native SDK session.
 */
internal object ModelTransferState {
    private const val PREFERENCES = "harnex_model_transfers"

    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun snapshot(context: Context, kind: ModelTransferKind): ModelTransferSnapshot {
        val preferences = preferences(context)
        val prefix = kind.name
        return ModelTransferSnapshot(
            phase = runCatching {
                ModelTransferPhase.valueOf(preferences.getString("$prefix.phase", "") ?: "")
            }.getOrDefault(ModelTransferPhase.IDLE),
            status = preferences.getString("$prefix.status", "") ?: "",
            revision = preferences.getLong("$prefix.revision", 0L),
            cancelRequested = preferences.getBoolean("$prefix.cancel", false),
        )
    }

    fun start(context: Context, kind: ModelTransferKind) {
        preferences(context).edit()
            .putBoolean("${kind.name}.cancel", false)
            .apply()
        update(context, kind, ModelTransferPhase.RUNNING, "Waiting for Android to start the download…")
    }

    fun cancelRequested(context: Context, kind: ModelTransferKind): Boolean =
        preferences(context).getBoolean("${kind.name}.cancel", false)

    fun requestCancel(context: Context, kind: ModelTransferKind) {
        preferences(context).edit().putBoolean("${kind.name}.cancel", true).apply()
        update(context, kind, ModelTransferPhase.CANCELLED, "Download paused · tap Prepare to resume")
    }

    fun update(context: Context, kind: ModelTransferKind, phase: ModelTransferPhase, status: String) {
        val preferences = preferences(context)
        val revision = preferences.getLong("${kind.name}.revision", 0L) + 1L
        preferences.edit()
            .putString("${kind.name}.phase", phase.name)
            .putString("${kind.name}.status", status.take(160))
            .putLong("${kind.name}.revision", revision)
            .apply()
    }
}
