package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UI and input ownership belong exclusively to this external app, not Harnex.
 * All public methods are called on the Android main thread.
 */
internal class EmbeddedChatScreen(
    private val activity: Activity,
    private val onPrepare: () -> Unit,
    private val onSend: () -> Unit,
    private val onStop: () -> Unit,
    private val onNewChat: () -> Unit,
    private val onPrepareVoice: () -> Unit,
    private val onVoice: () -> Unit,
    private val onCancelVoice: () -> Unit,
) {
    lateinit var prompt: EditText
        private set

    private lateinit var status: TextView
    private lateinit var diagnostics: TextView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var setupButton: Button
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button
    private lateinit var voiceStatus: TextView
    private lateinit var voicePrepareButton: Button
    private lateinit var voiceButton: Button
    private lateinit var voiceCancelButton: Button
    private var chatPhase: ChatPhase = ChatPhase.NEEDS_MODEL
    private var voicePhase: VoicePhase = VoicePhase.NEEDS_MODEL

    fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 249, 252))
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 14))
            setBackgroundColor(Color.rgb(25, 29, 40))
        }
        header.addView(chatLabel(activity, "Harnex · Local Chat", 22f, Color.WHITE))
        header.addView(
            chatLabel(activity, "EMBEDDED DEMO  ·  QWEN3.5 2B  ·  ON-DEVICE", 11f, Color.rgb(188, 201, 224)),
        )
        root.addView(header)

        val setup = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 8))
        }
        status = chatLabel(activity, "Model not ready · start local setup", 15f, Color.rgb(38, 54, 79))
        diagnostics = chatLabel(activity, "No generation yet · temporary history only", 12f, Color.rgb(91, 103, 121))
        setupButton = chatButton(activity, "Prepare local model", true).apply { setOnClickListener { onPrepare() } }
        setup.addView(status)
        setup.addView(diagnostics)
        setup.addView(
            setupButton,
            LinearLayout.LayoutParams(-1, dp(activity, 48)).apply { topMargin = dp(activity, 8) },
        )
        root.addView(setup)

        scroll = ScrollView(activity).apply {
            clipToPadding = false
            isFillViewport = true
        }
        messages = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12))
        }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        resetConversation()
        root.addView(buildComposer())

        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(0, bars.top, 0, maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
        return root
    }

    private fun buildComposer(): LinearLayout {
        val composer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
            setBackgroundColor(Color.WHITE)
        }
        prompt = EditText(activity).apply {
            hint = "Type a message…"
            minLines = 1
            maxLines = 3
            textSize = 16f
            setSingleLine(false)
            setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        composer.addView(prompt, LinearLayout.LayoutParams(-1, -2))
        voiceStatus = chatLabel(activity, "Voice model not installed · ~190 MB download", 12f, Color.rgb(91, 103, 121))
        composer.addView(voiceStatus)
        val voiceActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        voicePrepareButton = chatButton(activity, "Prepare Whisper Small Q5", false).apply {
            setOnClickListener { onPrepareVoice() }
        }
        voiceButton = chatButton(activity, "Record voice", false).apply {
            setOnClickListener { onVoice() }
        }
        voiceCancelButton = chatButton(activity, "Cancel audio", false).apply {
            setOnClickListener { onCancelVoice() }
        }
        for (button in listOf(voicePrepareButton, voiceButton, voiceCancelButton)) {
            voiceActions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(activity, 48), 1f).apply {
                    marginStart = dp(activity, 2)
                    marginEnd = dp(activity, 2)
                },
            )
        }
        composer.addView(voiceActions)
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sendButton = chatButton(activity, "Send", true).apply { setOnClickListener { onSend() } }
        stopButton = chatButton(activity, "Stop", false).apply { setOnClickListener { onStop() } }
        resetButton = chatButton(activity, "New chat", false).apply { setOnClickListener { onNewChat() } }
        for (button in listOf(sendButton, stopButton, resetButton)) {
            actions.addView(
                button,
                LinearLayout.LayoutParams(0, dp(activity, 52), 1f).apply {
                    marginStart = dp(activity, 2)
                    marginEnd = dp(activity, 2)
                },
            )
        }
        composer.addView(actions)
        return composer
    }

    fun resetConversation() {
        messages.removeAllViews()
        messages.addView(
            chatLabel(
                activity,
                "Private by design\n\nThis chat runs with Qwen3.5 2B inside this app. " +
                    "Messages stay in memory only, and only the last six completed turns " +
                    "are considered for each request. Ask a question when the model is ready.",
                15f,
                Color.rgb(78, 91, 114),
            ).apply { setPadding(dp(activity, 8), dp(activity, 16), dp(activity, 8), dp(activity, 16)) },
        )
    }

    fun addBubble(author: String, body: String, isUser: Boolean): TextView {
        val item = TextView(activity).apply {
            text = "$author\n$body"
            textSize = 15f
            setTextColor(if (isUser) Color.WHITE else Color.rgb(34, 47, 70))
            setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12))
            background = GradientDrawable().apply {
                setColor(if (isUser) Color.rgb(37, 91, 191) else Color.WHITE)
                cornerRadius = dp(activity, 15).toFloat()
            }
        }
        messages.addView(
            item,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(activity, 10)
                if (isUser) marginStart = dp(activity, 35) else marginEnd = dp(activity, 25)
            },
        )
        scroll.post { if (!activity.isDestroyed) scroll.fullScroll(View.FOCUS_DOWN) }
        return item
    }

    fun updateBubble(bubble: TextView, text: String) {
        val nearBottom = scroll.scrollY + scroll.height >= messages.height - dp(activity, 96)
        bubble.text = "HARNEX · QWEN3.5\n$text"
        if (nearBottom) scroll.post { if (!activity.isDestroyed) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun setStatus(message: String) {
        status.text = message
    }

    fun setDiagnostics(message: String) {
        diagnostics.text = message
    }

    fun setVoiceStatus(status: String) {
        voiceStatus.text = status
    }

    fun updateVoicePhase(phase: VoicePhase) {
        voicePhase = phase
        updateVoiceControls()
    }

    fun insertTranscript(text: String) {
        val current = prompt.text.toString().trim()
        val combined = if (current.isEmpty()) text else "$current $text"
        prompt.setText(combined)
        prompt.setSelection(combined.length)
        if (combined.length > 600) setVoiceStatus("Transcript exceeds 600 characters · shorten before Send")
    }

    fun updateControls(phase: ChatPhase) {
        chatPhase = phase
        val ready = phase == ChatPhase.READY
        val preparing = phase == ChatPhase.PREPARING
        val generating = phase == ChatPhase.GENERATING
        setupButton.visibility = if (ready || generating || phase == ChatPhase.STOPPING) View.GONE else View.VISIBLE
        setupButton.isEnabled = phase == ChatPhase.NEEDS_MODEL
        val voiceBusy = voicePhase == VoicePhase.RECORDING ||
            voicePhase == VoicePhase.TRANSCRIBING || voicePhase == VoicePhase.CANCELLING
        sendButton.isEnabled = ready && !voiceBusy
        sendButton.backgroundTintList = ColorStateList.valueOf(
            if (ready) Color.rgb(37, 91, 191) else Color.rgb(231, 236, 245),
        )
        sendButton.setTextColor(if (ready) Color.WHITE else Color.rgb(91, 103, 121))
        prompt.isEnabled = ready && !voiceBusy
        stopButton.isEnabled = preparing || generating
        resetButton.isEnabled = ready && !voiceBusy
        updateVoiceControls()
    }

    private fun updateVoiceControls() {
        val ready = chatPhase == ChatPhase.READY
        val voiceBusy = voicePhase == VoicePhase.RECORDING ||
            voicePhase == VoicePhase.TRANSCRIBING || voicePhase == VoicePhase.CANCELLING
        voicePrepareButton.visibility = if (voicePhase == VoicePhase.NEEDS_MODEL ||
            voicePhase == VoicePhase.PREPARING
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        voicePrepareButton.isEnabled = ready && voicePhase == VoicePhase.NEEDS_MODEL
        voicePrepareButton.text = if (voicePhase == VoicePhase.PREPARING) {
            "Preparing voice…"
        } else {
            "Prepare Whisper Small Q5"
        }
        voiceButton.visibility = if (voicePhase == VoicePhase.READY ||
            voicePhase == VoicePhase.RECORDING
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        voiceButton.text = if (voicePhase == VoicePhase.RECORDING) "Finish recording" else "Record voice"
        voiceButton.isEnabled = ready
        voiceCancelButton.visibility = if (voicePhase == VoicePhase.PREPARING ||
            voicePhase == VoicePhase.RECORDING || voicePhase == VoicePhase.TRANSCRIBING ||
            voicePhase == VoicePhase.CANCELLING
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        voiceCancelButton.isEnabled = voicePhase != VoicePhase.CANCELLING
        val canEdit = ready && !voiceBusy
        prompt.isEnabled = canEdit
        sendButton.isEnabled = canEdit
        resetButton.isEnabled = canEdit
    }
}

private fun dp(activity: Activity, value: Int) = (value * activity.resources.displayMetrics.density).toInt()

private fun chatLabel(activity: Activity, value: String, size: Float, color: Int) = TextView(activity).apply {
    text = value
    textSize = size
    setTextColor(color)
    setPadding(dp(activity, 2), dp(activity, 4), dp(activity, 2), dp(activity, 4))
}

private fun chatButton(activity: Activity, text: String, primary: Boolean) = Button(activity).apply {
    this.text = text
    isAllCaps = false
    textSize = 14f
    setTextColor(if (primary) Color.WHITE else Color.rgb(36, 50, 71))
    backgroundTintList = ColorStateList.valueOf(
        if (primary) Color.rgb(37, 91, 191) else Color.rgb(231, 236, 245),
    )
}
