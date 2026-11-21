package io.github.daniele21.localllm.externalembedded

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal data class VisionActions(val onPrepare: () -> Unit, val onChoose: () -> Unit, val onRemove: () -> Unit)

/** Single owner of optional photo picker controls and transient preview. */
internal class EmbeddedVisionComposer(
    private val activity: Activity,
    private val actions: VisionActions,
    private val onAvailabilityChanged: () -> Unit,
) {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var prepareButton: Button
    private lateinit var chooseButton: Button
    private lateinit var removeButton: Button

    var ready = false
        private set
    var preparing = false
        private set
    var hasImage = false
        private set

    fun build(): LinearLayout {
        val container = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        status = chatLabel(
            activity,
            "Photo questions · prepare vision model (~668 MB) once",
            12f,
            Color.rgb(91, 103, 121),
        )
        container.addView(status)
        preview = ImageView(activity).apply {
            contentDescription = "Selected private photo preview"
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, dp(activity, 120)))
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prepareButton = chatButton(activity, "Prepare vision", false).apply {
            setOnClickListener { actions.onPrepare() }
        }
        chooseButton = chatButton(activity, "Choose photo", false).apply {
            setOnClickListener { actions.onChoose() }
            contentDescription = "Select one photo from Android Photo Picker"
        }
        removeButton = chatButton(activity, "Remove", false).apply {
            setOnClickListener { actions.onRemove() }
            contentDescription = "Remove selected photo from this message"
        }
        buttons.addEqualWidthButtons(activity, listOf(prepareButton, chooseButton, removeButton), 44)
        container.addView(buttons)
        return container
    }

    fun setPhoto(bitmap: Bitmap) {
        hasImage = true
        preview.setImageBitmap(bitmap)
        preview.visibility = View.VISIBLE
        onAvailabilityChanged()
    }

    fun clearPhoto() {
        hasImage = false
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        onAvailabilityChanged()
    }

    fun setStatus(value: String) {
        status.text = value
    }

    fun setReady(ready: Boolean, preparing: Boolean) {
        this.ready = ready
        this.preparing = preparing
        onAvailabilityChanged()
    }

    fun updateControls(phase: ChatPhase, voicePhase: VoicePhase): Boolean {
        val canAct = phase == ChatPhase.READY && !voicePhase.isBusy && !preparing
        prepareButton.visibility = if (ready) View.GONE else View.VISIBLE
        prepareButton.isEnabled = canAct
        prepareButton.text = if (preparing) "Preparing vision…" else "Prepare vision"
        chooseButton.isEnabled = canAct
        removeButton.visibility = if (hasImage) View.VISIBLE else View.GONE
        removeButton.isEnabled = canAct
        return canAct
    }
}
