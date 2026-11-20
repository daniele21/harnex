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
        status = chatLabel(activity, "Model not ready · start local setup", 15f, Color.rgb(38, 54, 79)).apply {
            contentDescription = "Model status"
        }
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
                    "are considered for each request. Start by preparing the model.",
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
        bubble.text = "HARNEX · QWEN3.5\n$text"
        scroll.post { if (!activity.isDestroyed) scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun setStatus(message: String) {
        status.text = message
    }

    fun setDiagnostics(message: String) {
        diagnostics.text = message
    }

    fun updateControls(phase: ChatPhase) {
        val ready = phase == ChatPhase.READY
        val preparing = phase == ChatPhase.PREPARING
        val generating = phase == ChatPhase.GENERATING
        setupButton.visibility = if (ready || generating || phase == ChatPhase.STOPPING) View.GONE else View.VISIBLE
        setupButton.isEnabled = phase == ChatPhase.NEEDS_MODEL
        sendButton.isEnabled = ready
        prompt.isEnabled = ready
        stopButton.isEnabled = preparing || generating
        resetButton.isEnabled = ready
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
