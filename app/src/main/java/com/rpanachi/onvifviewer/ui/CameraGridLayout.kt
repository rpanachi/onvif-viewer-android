package com.rpanachi.onvifviewer.ui

import android.content.Context
import android.view.ViewGroup
import com.rpanachi.onvifviewer.model.GridGeometry
import com.rpanachi.onvifviewer.model.GridSpec
import com.rpanachi.onvifviewer.model.LayoutPresets

/** Positions its children in the cells of a [GridSpec]; child i goes to cell i. Used for the label overlay. */
class CameraGridLayout(context: Context) : ViewGroup(context) {

    var spec: GridSpec = LayoutPresets.spec("2x2", true)
        set(value) {
            field = value
            requestLayout()
        }

    private val gapPx = GridGeometry.gapPx(resources.displayMetrics.density)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            val r = GridGeometry.cellRect(spec, i, w, h, gapPx)
            getChildAt(i).measure(exact(r.width()), exact(r.height()))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            val rc = GridGeometry.cellRect(spec, i, w, h, gapPx)
            getChildAt(i).layout(rc.left, rc.top, rc.right, rc.bottom)
        }
    }

    fun childIndexAt(x: Float, y: Float): Int {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return i
        }
        return -1
    }

    private fun exact(v: Int) = MeasureSpec.makeMeasureSpec(v.coerceAtLeast(0), MeasureSpec.EXACTLY)
}
