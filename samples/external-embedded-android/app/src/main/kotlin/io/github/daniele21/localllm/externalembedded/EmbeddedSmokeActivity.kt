package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Standalone Android chat consumer. Runtime, model and cancellation use Harnex Embedded SDK;
 * conversation messages and UI state stay within this app.
 */
class EmbeddedSmokeActivity : Activity() {
    private lateinit var screen: EmbeddedChatScreen
    private lateinit var controller: EmbeddedChatController
    private lateinit var voice: SpeechInputController
    private val photoWorker = Executors.newSingleThreadExecutor()
    private var selectedPhoto: SelectedPhoto? = null
    private lateinit var transferUi: ModelTransferUiController
    private val voiceEvents = object : VoiceObserver {
        override fun onStart() {
        super.onStart()
        transferUi.onStart()
    }

    override fun onStop() {
        transferUi.onStop()
        // Android can background the Activity without destroying it. Never leave
        // its microphone or native transcription running out of sight.
        voice.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        photoWorker.shutdownNow()
        removePhoto()
        voice.close()
        controller.close()
        super.onDestroy()
    }

    private companion object {
        const val MIC_PERMISSION_REQUEST = 410
        const val PHOTO_REQUEST = 421
    }
}

private fun Activity.ui(action: () -> Unit) {
    runOnUiThread { if (!isDestroyed && !isFinishing) action() }
}
