package com.rpanachi.onvifviewer.ui

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** Transparent overlay for one grid cell: the camera name and its connection status. Video is drawn underneath by the compositor. */
class CameraTileView(context: Context) : FrameLayout(context) {

    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 11f
        setBackgroundColor(0x80000000.toInt())
        setPadding(8, 2, 8, 2)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    var showLabel: Boolean = true
        set(value) {
            field = value
            render()
        }

    private var name = ""
    private var status: String? = null

    init {
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        render()
    }

    fun setStatus(name: String, status: String?) {
        this.name = name
        this.status = status
        render()
    }

    private fun render() {
        label.text = if (status == null) name else "$name - $status"
        label.visibility = if (showLabel || status != null) VISIBLE else GONE
    }
}
