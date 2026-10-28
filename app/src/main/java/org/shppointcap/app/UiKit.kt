package org.shppointcap.app

/** Minimal spinner/text listeners without extra imports (shared settings UI). */
class SimpleSelected(val onPos: (Int) -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long,
    ) = onPos(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
}

class SimpleWatcher(val onText: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) {
        onText(s?.toString().orEmpty())
    }
}

/**
 * The colours Forest Navigator uses, so both apps read the same way.
 *
 * A crew who learns one app should not have to learn the other. Three kinds of control,
 * told apart by colour alone, so the right one is easy to hit without reading:
 *
 *  - function button : yellow on black, thin red border. Something you can do.
 *  - display field  : dark blue with yellow text, thin green border. A value, read only.
 *  - command        : orange on black. The main commands that start and stop things.
 *
 * Colour is never the only cue: every one of these also carries its own label.
 */
object Palette {
    const val ACTION_FILL = 0xFFFFD400.toInt()
    const val ACTION_BORDER = 0xFFD32F2F.toInt()
    const val ACTION_TEXT = 0xFF000000.toInt()

    const val COMMAND_FILL = 0xFFFF8C00.toInt()
    const val COMMAND_BORDER = 0xFF8A3D00.toInt()

    const val DISPLAY_FILL = 0xFF10314F.toInt()
    const val DISPLAY_BORDER = 0xFF2E7D32.toInt()
    const val DISPLAY_TEXT = 0xFFFFD54F.toInt()

    /** Add_Point: red on dark blue, so the one button used in a hurry stands out. */
    const val ADD_POINT_FILL = 0xFFC62828.toInt()
    const val ADD_POINT_BORDER = 0xFFFFD54F.toInt()
    const val ADD_POINT_TEXT = 0xFF10233F.toInt()

    const val NAV_FILL = 0xFFA5D6A7.toInt()
    const val NAV_BORDER = 0xFF1565C0.toInt()

    /** Caption colour for controls that name something. Dark enough to read on white. */
    const val CAPTION = 0xFF4A4A4A.toInt()
}

/** A yellow button with a red border: something the user can do. */
fun android.content.Context.actionButton(text: String): android.widget.Button =
    android.widget.Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Palette.ACTION_TEXT)
        background = rounded(Palette.ACTION_FILL, Palette.ACTION_BORDER)
    }

/** An orange button: a command that starts or stops something. */
fun android.content.Context.commandButton(text: String): android.widget.Button =
    android.widget.Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Palette.ACTION_TEXT)
        background = rounded(Palette.COMMAND_FILL, Palette.COMMAND_BORDER)
    }

/** A read-only value: dark blue, yellow text, green border. */
fun android.content.Context.displayField(text: String): android.widget.TextView =
    android.widget.TextView(this).apply {
        this.text = text
        setTextColor(Palette.DISPLAY_TEXT)
        background = rounded(Palette.DISPLAY_FILL, Palette.DISPLAY_BORDER)
        setPadding(
            (8 * resources.displayMetrics.density).toInt(),
            (4 * resources.displayMetrics.density).toInt(),
            (8 * resources.displayMetrics.density).toInt(),
            (4 * resources.displayMetrics.density).toInt(),
        )
    }

private fun android.content.Context.rounded(fill: Int, border: Int): android.graphics.drawable.Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        setColor(fill)
        val w = (2 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        setStroke(w, border)
        cornerRadius = (4 * resources.displayMetrics.density).toInt().toFloat()
    }
