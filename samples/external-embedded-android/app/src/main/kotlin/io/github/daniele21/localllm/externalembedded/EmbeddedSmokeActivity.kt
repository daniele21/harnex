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
        override fun onVoicePhase(phase: VoicePhase) {
            ui { screen.updateVoicePhase(phase) }
        }

        override fun onVoiceStatus(status: String) {
            ui { screen.voiceStatusText = status }
        }

        override fun onVoiceTranscript(text: String) {
            ui { screen.insertTranscript(text) }
        }
    }

    @Volatile
    private var chatPhase: ChatPhase = ChatPhase.NEEDS_MODEL
    private var activeRunId = -1L
    private var answerBubble: TextView? = null

    private val chatEvents = object : ChatObserver {
        override fun onPhase(phase: ChatPhase) {
            chatPhase = phase
            this@EmbeddedSmokeActivity.ui {
                screen.updateControls(phase)
                if (phase == ChatPhase.READY && ::transferUi.isInitialized) transferUi.render()
            }
        }

        override fun onStatus(status: String) {
            this@EmbeddedSmokeActivity.ui { screen.setStatus(status) }
        }

        override fun onDiagnostics(text: String) {
            this@EmbeddedSmokeActivity.ui { screen.diagnosticsText = text }
        }

        override fun onTurnStarted(id: Long, input: String) {
            this@EmbeddedSmokeActivity.ui {
                activeRunId = id
                screen.addBubble("YOU", input, true)
                answerBubble = screen.addBubble("HARNEX · QWEN3.5", "Thinking locally…", false)
            }
        }

        override fun onAnswerDelta(id: Long, answer: String) {
            this@EmbeddedSmokeActivity.ui {
                if (activeRunId == id && controller.isGenerating()) answerBubble?.let { screen.updateBubble(it, answer) }
            }
        }

        override fun onVisionState(ready: Boolean, preparing: Boolean) {
            this@EmbeddedSmokeActivity.ui { screen.updateVisionState(ready, preparing) }
        }

        override fun onVisionStatus(status: String) {
            this@EmbeddedSmokeActivity.ui { screen.visionStatusText(status) }
        }

        override fun onTurnFinished(id: Long, answer: String, saved: Boolean, retryPrompt: String?) {
            this@EmbeddedSmokeActivity.ui {
                if (activeRunId != id) return@ui
                answerBubble?.let { bubble ->
                    screen.updateBubble(bubble, if (saved) answer else "Not saved · $answer")
                }
                if (retryPrompt != null && screen.prompt.text.isBlank()) screen.prompt.setText(retryPrompt)
                answerBubble = null
                activeRunId = -1L
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EmbeddedChatScreen(
            activity = this,
            onPrepare = { transferUi.start(ModelTransferKind.MODEL) },
            onSend = { send() },
            onStop = { if (!transferUi.cancelIfRunning()) controller.stop() },
            onNewChat = { reset() },
            onPrepareVoice = { voice.prepare() },
            onVoice = { voiceAction() },
            onCancelVoice = { voice.cancel() },
            onPrepareVision = { transferUi.start(ModelTransferKind.VISION) },
            onChoosePhoto = { choosePhoto() },
            onRemovePhoto = { removePhoto() },
        )
        val content = screen.build()
        setContentView(content)
        content.requestApplyInsets()
        controller = EmbeddedChatController(applicationContext, chatEvents)
        voice = SpeechInputController(applicationContext, voiceEvents)
        transferUi = ModelTransferUiController(this, screen, controller) { chatPhase }
        screen.updateControls(ChatPhase.NEEDS_MODEL)
    }

    override fun onStart() {
        super.onStart()
        transferUi.onStart()
    }

    private fun voiceAction() {
        if (voice.phase == VoicePhase.RECORDING) {
            voice.finish()
            return
        }
        if (chatPhase != ChatPhase.READY || voice.phase != VoicePhase.READY) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        } else {
            voice.record()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ModelTransferUiController.NOTIFICATION_PERMISSION_REQUEST) {
            transferUi.onNotificationPermissionResult()
            return
        }
        if (requestCode != MIC_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && chatPhase == ChatPhase.READY) {
            voice.record()
        } else {
            screen.voiceStatusText = "Microphone permission denied · enable it in Android Settings"
        }
    }

    private fun send() {
        if (
            voice.phase == VoicePhase.RECORDING ||
            voice.phase == VoicePhase.TRANSCRIBING ||
            voice.phase == VoicePhase.CANCELLING
        ) {
            return
        }
        val message = screen.prompt.text.toString()
        val photo = selectedPhoto
        val accepted = if (photo == null) controller.send(message) else controller.send(message, photo.jpegBytes)
        if (accepted) {
            screen.prompt.text.clear()
            removePhoto()
        }
    }

    private fun reset() {
        if (!controller.clearConversation()) return
        activeRunId = -1L
        answerBubble = null
        screen.prompt.text.clear()
        removePhoto()
        screen.resetConversation()
    }

    private fun choosePhoto() {
        if (chatPhase != ChatPhase.READY || voice.phase.isBusy) return
        val request = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
        } else {
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
        }
        startActivityForResult(request, PHOTO_REQUEST)
    }

    @Deprecated("Used for bounded Android Photo Picker compatibility on API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PHOTO_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        screen.visionStatusText("Reading selected photo locally…")
        photoWorker.execute {
            val decoded = runCatching { PhotoDecoder.decode(contentResolver, uri) }
            ui {
                decoded.fold(
                    onSuccess = { photo ->
                        removePhoto()
                        selectedPhoto = photo
                        screen.showPhoto(photo.preview)
                        screen.visionStatusText("Photo selected · type a question and tap Send")
                    },
                    onFailure = { failure ->
                        screen.visionStatusText("Unable to read photo · ${failure.message ?: "try another image"}")
                    },
                )
            }
        }
    }

    private fun removePhoto() {
        val previous = selectedPhoto
        selectedPhoto = null
        if (::screen.isInitialized) screen.removePhoto()
        previous?.preview?.recycle()
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
