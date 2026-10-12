package io.github.daniele21.harnex.hello

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import io.github.daniele21.localllm.transport.binder.client.SharedRuntimeConnectionState
import java.security.MessageDigest

/** Minimal UI shell around the lifecycle-owned [HelloHarnexViewModel]. */
class MainActivity : ComponentActivity() {
    private lateinit var viewModel: HelloHarnexViewModel
    private lateinit var connectionValue: TextView
    private lateinit var statusValue: TextView
    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var metrics: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var runButton: Button
    private lateinit var cancelButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        viewModel =
            ViewModelProvider(
                this,
                HelloHarnexViewModel.Factory(applicationContext),
            )[HelloHarnexViewModel::class.java]
        viewModel.state.observe(this, ::render)
    }

    private fun buildContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        root.addView(label("Hello Harnex", 30f, Typeface.BOLD))
        root.addView(space(6))
        root.addView(label("One external Android app. One Harnex host. One local inference.", 16f))
        root.addView(space(24))
        addAuthorizationSection(root, currentSignerSha256())
        addConnectionSection(root)
        addInferenceSection(root)
        return ScrollView(this).apply { addView(root) }
    }

    private fun addAuthorizationSection(root: LinearLayout, signer: String) {
        root.addView(section("1 · Authorize this app in Harnex"))
        root.addView(label("Open Harnex → Apps → New app connection and use these exact values:", 15f))
        root.addView(space(12))
        root.addView(keyValue("Display name", "Hello Harnex"))
        root.addView(keyValue("Harnex app ID", "hello-harnex"))
        root.addView(keyValue("Android package", packageName))
        root.addView(keyValue("Signer SHA-256", signer, selectable = true))
        root.addView(keyValue("Use case", "Generic text generation"))
        root.addView(keyValue("Initial preset", "Quality"))
        root.addView(
            Button(this).apply {
                text = "Copy signer SHA-256"
                isAllCaps = false
                setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Hello Harnex signer SHA-256", signer))
                    Toast.makeText(this@MainActivity, "Signer copied", Toast.LENGTH_SHORT).show()
                }
            },
        )
        root.addView(space(24))
    }

    private fun addConnectionSection(root: LinearLayout) {
        root.addView(section("2 · Connect"))
        root.addView(keyValue("Configured Harnex package", BuildConfig.HARNEX_HOST_PACKAGE))
        connectionValue = label("DISCONNECTED", 15f, Typeface.BOLD)
        root.addView(connectionValue)
        val connectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        connectButton = Button(this).apply {
            text = "Connect to Harnex"
            isAllCaps = false
            setOnClickListener { viewModel.connect() }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { viewModel.disconnect() }
        }
        connectionActions.addView(
            connectButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        connectionActions.addView(
            disconnectButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(connectionActions, matchWidth())
        root.addView(space(24))
    }

    private fun addInferenceSection(root: LinearLayout) {
        root.addView(section("3 · Run local inference"))
        root.addView(
            label(
                "Send any bounded text prompt through the host-owned Generic text generation use case.",
                15f,
            ),
        )
        input = EditText(this).apply {
            setText("Explain in two concise sentences why on-device AI can improve privacy.")
            minLines = 3
            gravity = Gravity.TOP
            hint = "Prompt for local text generation"
            addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        if (::viewModel.isInitialized) viewModel.updateInput(s?.toString().orEmpty())
                    }
                },
            )
        }
        root.addView(input, matchWidth())
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        runButton = Button(this).apply {
            text = "Run on device"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { viewModel.runInference() }
        }
        cancelButton = Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { viewModel.cancel() }
        }
        actions.addView(
            runButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        actions.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        root.addView(actions, matchWidth())
        root.addView(space(16))

        statusValue = label("Authorize the app in Harnex, then connect.", 14f)
        root.addView(statusValue)
        root.addView(space(16))
        root.addView(label("Result", 16f, Typeface.BOLD))
        output = label("No inference yet.", 15f).apply { setTextIsSelectable(true) }
        root.addView(output)
        metrics = label("", 13f)
        root.addView(metrics)
        root.addView(space(28))
        root.addView(label("What this proves", 16f, Typeface.BOLD))
        root.addView(
            label(
                "This app has no GGUF files, JNI code or Harnex source dependency. It uses only the published Consumer SDK, an explicit Binder host identity and a Harnex-authorized use case.",
                14f,
            ),
        )
    }

    private fun render(state: HelloHarnexUiState) {
        connectionValue.text = buildString {
            append(state.connectionState.name)
            state.negotiatedMinor?.let { append(" · protocol minor $it") }
        }
        statusValue.text = state.status
        output.text = state.output
        metrics.text = state.metrics
        if (input.text.toString() != state.input) {
            input.setText(state.input)
            input.setSelection(state.input.length)
        }

        val activeConnection = state.connectionState in ACTIVE_CONNECTION_STATES
        connectButton.isEnabled = !activeConnection
        disconnectButton.isEnabled = activeConnection && !state.inferenceRunning
        runButton.isEnabled = state.connected && !state.inferenceRunning
        cancelButton.isEnabled = state.inferenceRunning
        input.isEnabled = !state.inferenceRunning
    }

    private companion object {
        val ACTIVE_CONNECTION_STATES =
            setOf(
                SharedRuntimeConnectionState.BINDING,
                SharedRuntimeConnectionState.NEGOTIATING,
                SharedRuntimeConnectionState.CONNECTED,
            )
    }
}

@Suppress("DEPRECATION")
private fun Context.currentSignerSha256(): String {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val packageInfo = packageManager.getPackageInfo(packageName, flags)
    val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        requireNotNull(packageInfo.signingInfo).apkContentsSigners.orEmpty().toList()
    } else {
        packageInfo.signatures.orEmpty().toList()
    }
    val signature = signatures.firstOrNull() ?: error("App signing certificate is unavailable")
    return MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xff) }
}

private fun Context.section(text: String): TextView = label(text, 20f, Typeface.BOLD).apply {
    setPadding(0, 0, 0, dp(10))
}

private fun Context.keyValue(key: String, value: String, selectable: Boolean = false): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(0, dp(4), 0, dp(8))
    addView(label(key, 12f, Typeface.BOLD))
    addView(label(value, 14f).apply { setTextIsSelectable(selectable) })
}

private fun Context.label(text: String, sizeSp: Float, style: Int = Typeface.NORMAL): TextView = TextView(this).apply {
    this.text = text
    textSize = sizeSp
    setTypeface(typeface, style)
    setLineSpacing(0f, 1.12f)
}

private fun Context.space(heightDp: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
}

private fun Context.matchWidth() = LinearLayout.LayoutParams(
    LinearLayout.LayoutParams.MATCH_PARENT,
    LinearLayout.LayoutParams.WRAP_CONTENT,
)

private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
