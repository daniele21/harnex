package io.github.daniele21.localllm.externalembedded

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView

/**
 * Standalone Android chat consumer. Runtime, model and cancellation use Harnex Embedded SDK;
 * conversation messages and UI state stay within this app.
 */
class EmbeddedSmokeActivity : Activity(), VoiceObserver {
    private lateinit var screen: EmbeddedChatScreen
    private lateinit var controller: EmbeddedChatController
    private lateinit var voice: SpeechInputController

    @Volatile
    private var chatPhase: ChatPhase = ChatPhase.NEEDS_MODEL
    private var activeRunId = -1L
    private var answerBubble: TextView? = null

    private val chatEvents = object : ChatObserver {
        override fun onPhase(phase: ChatPhase) {
            chatPhase = phase
            this@EmbeddedSmokeActivity.ui { screen.updateControls(phase) }
        }

        override fun onStatus(status: String) {
            this@EmbeddedSmokeActivity.ui { screen.setStatus(status) }
        }

        override fun onDiagnostics(text: String) {
            this@EmbeddedSmokeActivity.ui { screen.setDiagnostics(text) }
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
            onPrepare = { controller.prepare() },
            onSend = { send() },
            onStop = { controller.stop() },
            onNewChat = { reset() },
            onPrepareVoice = { voice.prepare() },
            onVoice = { voiceAction() },
            onCancelVoice = { voice.cancel() },
        )
        val content = screen.build()
        setContentView(content)
        content.requestApplyInsets()
        controller = EmbeddedChatController(applicationContext, chatEvents)
        voice = SpeechInputController(applicationContext, this)
        screen.updateControls(ChatPhase.NEEDS_MODEL)
    }

    private fun voiceAction() {
        if (voice.current() == VoicePhase.RECORDING) {
            voice.finish()
            return
        }
        if (chatPhase != ChatPhase.READY || voice.current() != VoicePhase.READY) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_PERMISSION_REQUEST)
        } else {
            voice.record()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && chatPhase == ChatPhase.READY) {
            voice.record()
        } else {
            onVoiceStatus("Microphone permission denied · enable it in Android Settings")
        }
    }

    private fun send() {
        if (
            voice.current() == VoicePhase.RECORDING ||
            voice.current() == VoicePhase.TRANSCRIBING ||
            voice.current() == VoicePhase.CANCELLING
        ) {
            return
        }
        val message = screen.prompt.text.toString()
        if (controller.send(message)) screen.prompt.text.clear()
    }

    private fun reset() {
        if (!controller.clearConversation()) return
        activeRunId = -1L
        answerBubble = null
        screen.prompt.text.clear()
        screen.resetConversation()
    }

    override fun onVoicePhase(phase: VoicePhase) {
        ui { screen.updateVoicePhase(phase) }
    }

    override fun onVoiceStatus(status: String) {
        ui { screen.setVoiceStatus(status) }
    }

    override fun onVoiceTranscript(text: String) {
        ui { screen.insertTranscript(text) }
    }

    override fun onStop() {
        // Android can background the Activity without destroying it. Never leave
        // its microphone or native transcription running out of sight.
        voice.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        voice.close()
        controller.close()
        super.onDestroy()
    }
    private companion object {
        const val MIC_PERMISSION_REQUEST = 410
    }
}

private fun Activity.ui(action: () -> Unit) {
    runOnUiThread { if (!isDestroyed && !isFinishing) action() }
}
