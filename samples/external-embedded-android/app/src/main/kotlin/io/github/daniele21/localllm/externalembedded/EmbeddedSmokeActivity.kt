package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Standalone Android chat consumer. Runtime, model and cancellation use Harnex Embedded SDK;
 * conversation messages and UI state stay within this app.
 */
class EmbeddedSmokeActivity :
    Activity(),
    ChatObserver {
    private lateinit var screen: EmbeddedChatScreen
    private lateinit var controller: EmbeddedChatController
    private var activeRunId = -1L
    private var answerBubble: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EmbeddedChatScreen(
            activity = this,
            onPrepare = { controller.prepare() },
            onSend = { send() },
            onStop = { controller.stop() },
            onNewChat = { reset() },
        )
        val content = screen.build()
        setContentView(content)
        content.requestApplyInsets()
        controller = EmbeddedChatController(applicationContext, this)
        screen.updateControls(ChatPhase.NEEDS_MODEL)
    }

    private fun send() {
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

    override fun onPhase(phase: ChatPhase) {
        ui { screen.updateControls(phase) }
    }

    override fun onStatus(status: String) {
        ui { screen.setStatus(status) }
    }

    override fun onDiagnostics(text: String) {
        ui { screen.setDiagnostics(text) }
    }

    override fun onTurnStarted(id: Long, input: String) {
        ui {
            activeRunId = id
            screen.addBubble("YOU", input, true)
            answerBubble = screen.addBubble("HARNEX · QWEN3.5", "Thinking locally…", false)
        }
    }

    override fun onAnswerDelta(id: Long, answer: String) {
        ui {
            if (activeRunId == id) answerBubble?.let { screen.updateBubble(it, answer) }
        }
    }

    override fun onTurnFinished(id: Long, answer: String, saved: Boolean, retryPrompt: String?) {
        ui {
            if (activeRunId != id) return@ui
            answerBubble?.let { bubble ->
                screen.updateBubble(bubble, if (saved) answer else "Not saved · $answer")
            }
            if (retryPrompt != null && screen.prompt.text.isBlank()) screen.prompt.setText(retryPrompt)
            answerBubble = null
            activeRunId = -1L
        }
    }

    private fun ui(action: () -> Unit) {
        runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}
