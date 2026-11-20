package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Debug-only UI journey canary. It does not open Harnex, load a model, or
 * simulate native inference. It exercises the actual chat screen with test text.
 */
class DebugChatJourneyActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var screen: EmbeddedChatScreen
    private var phase = ChatPhase.NEEDS_MODEL
    private var runId = 0
    private var lastPrompt = ""
    private var reply: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screen = EmbeddedChatScreen(
            activity = this,
            onPrepare = {
                phase = ChatPhase.READY
                screen.setStatus("Ready · UI-only test, no model loaded")
                screen.updateControls(phase)
            },
            onSend = ::send,
            onStop = ::stop,
            onPrepareVoice = {
                screen.setVoiceStatus("Whisper Small Q5 ready · UI-only test")
                screen.updateVoicePhase(VoicePhase.READY)
            },
            onVoice = {
                // This is a UI-only synthetic transcript; no microphone or native STT.
                screen.updateVoicePhase(VoicePhase.RECORDING)
                screen.setVoiceStatus("Recording · UI-only test")
                handler.postDelayed({
                    screen.updateVoicePhase(VoicePhase.TRANSCRIBING)
                    screen.setVoiceStatus("Transcribing · UI-only test")
                    handler.postDelayed({
                        screen.updateVoicePhase(VoicePhase.READY)
                        screen.insertTranscript("Dettatura locale di prova")
                        screen.setVoiceStatus("Voice transcript ready · UI-only test")
                    }, 400L)
                }, 400L)
            },
            onCancelVoice = {
                screen.updateVoicePhase(VoicePhase.READY)
                screen.setVoiceStatus("Voice cancelled · UI-only test")
            },
            onNewChat = {
                if (phase == ChatPhase.READY) {
                    screen.resetConversation()
                    screen.prompt.text.clear()
                    screen.setStatus("New chat · UI-only test")
                }
            },
        )
        val content = screen.build()
        setContentView(content)
        content.requestApplyInsets()
        screen.setStatus("UI-only emulator journey · no model loaded")
        screen.updateControls(phase)
    }

    private fun send() {
        if (phase != ChatPhase.READY) return
        val input = screen.prompt.text.toString().trim()
        if (input.isEmpty()) return
        lastPrompt = input
        screen.prompt.text.clear()
        screen.addBubble("YOU", input, true)
        reply = screen.addBubble("HARNEX · QWEN3.5", "UI-only streaming fixture…", false)
        phase = ChatPhase.GENERATING
        screen.updateControls(phase)
        screen.setStatus("Streaming · UI-only test")
        val thisRun = ++runId
        val deltas = listOf("Synthetic", "Synthetic text", "Synthetic text for UI verification")
        for ((index, delta) in deltas.withIndex()) {
            handler.postDelayed({
                if (phase == ChatPhase.GENERATING && runId == thisRun) {
                    reply?.let { screen.updateBubble(it, delta) }
                    if (index == deltas.lastIndex) {
                        phase = ChatPhase.READY
                        screen.setStatus("Completed · UI-only test")
                        screen.updateControls(phase)
                    }
                }
            }, (index + 1) * 2_000L)
        }
    }

    private fun stop() {
        if (phase != ChatPhase.GENERATING) return
        runId++
        phase = ChatPhase.READY
        reply?.let { screen.updateBubble(it, "Not saved · UI-only cancellation test") }
        screen.prompt.setText(lastPrompt)
        screen.setStatus("Stopped · UI-only test")
        screen.updateControls(phase)
    }

    override fun onDestroy() {
        runId++
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
