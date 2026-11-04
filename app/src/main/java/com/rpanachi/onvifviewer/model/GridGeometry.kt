package com.rpanachi.onvifviewer.model

import android.graphics.Rect

/** Pixel rectangle of each grid cell; shared by the label overlay and the GL compositor so they line up. */
object GridGeometry {
    fun cellRect(spec: GridSpec, index: Int, w: Int, h: Int, gap: Int): Rect {
        val cell = spec.cells.getOrNull(index) ?: return Rect(0, 0, 0, 0)
        fun x(col: Int) = (w.toLong() * col / spec.cols).toInt()
        fun y(row: Int) = (h.toLong() * row / spec.rows).toInt()
        val left = x(cell.col) + (if (cell.col > 0) gap else 0)
        val top = y(cell.row) + (if (cell.row > 0) gap else 0)
        val right = x(cell.col + cell.colSpan) - (if (cell.col + cell.colSpan < spec.cols) gap else 0)
        val bottom = y(cell.row + cell.rowSpan) - (if (cell.row + cell.rowSpan < spec.rows) gap else 0)
        return Rect(left, top, right, bottom)
    }

    fun gapPx(density: Float): Int = (1 * density).toInt().coerceAtLeast(1)
}
