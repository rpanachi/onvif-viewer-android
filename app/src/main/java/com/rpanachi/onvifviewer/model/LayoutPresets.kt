package com.rpanachi.onvifviewer.model

data class Cell(val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1)

data class GridSpec(val cols: Int, val rows: Int, val cells: List<Cell>) {
    val count: Int get() = cells.size
}

/** A selectable layout. [shape] is "columns x rows" for plain grids and "1 big + n" for the mixed ones. */
data class LayoutPreset(val id: String, val count: Int, val shape: String) {
    val label: String get() = if (count == 1) "1 camera" else "$count cameras ($shape)"
}

/**
 * Layouts come in two families, one per screen orientation, so that cells stay wider than tall either
 * way (cameras are landscape) and every layout covers the whole screen. The number of cameras a
 * layout holds therefore differs between its landscape and portrait form. The viewer remembers one
 * choice per orientation and switches between them when the device is rotated.
 */
object LayoutPresets {
    private const val DEFAULT_LANDSCAPE = "1+7"

    fun defaultId(landscape: Boolean): String = resolve(DEFAULT_LANDSCAPE, landscape) ?: "1x1"

    val landscape: List<LayoutPreset> = listOf(
        grid(1, 1), grid(2, 1), grid(2, 2), grid(3, 2), big(5), big(7), grid(3, 3), big(8), grid(4, 3), grid(4, 4),
    )

    val portrait: List<LayoutPreset> = listOf(
        grid(1, 1), grid(1, 2), grid(1, 3), grid(1, 4), big(4), grid(2, 3), big(6), grid(2, 4), big(8),
        grid(2, 5), grid(2, 6), grid(3, 5), grid(2, 8),
    )

    fun forOrientation(landscape: Boolean): List<LayoutPreset> = if (landscape) this.landscape else portrait

    fun byId(id: String, landscape: Boolean): LayoutPreset {
        val list = forOrientation(landscape)
        val resolved = resolve(id, landscape) ?: defaultId(landscape)
        return list.first { it.id == resolved }
    }

    /** Ids used before layouts were split per orientation; still accepted from old settings and the adb hook. */
    private val legacy = mapOf(
        "1" to "1x1", "4" to "2x2", "6" to "1+5", "8" to "1+7", "9" to "3x3", "9b" to "1+8", "12" to "4x3", "16" to "4x4",
    )

    private val portraitOf = mapOf(
        "2x1" to "1x2", "2x2" to "2x4", "3x2" to "2x3", "3x3" to "2x5", "4x3" to "2x6", "4x4" to "2x8",
        "1+5" to "1+6", "1+7" to "1+8",
    )

    private val landscapeOf = mapOf(
        "1x2" to "2x1", "1x3" to "2x2", "1x4" to "2x2", "2x3" to "3x2", "2x4" to "3x3", "2x5" to "3x3",
        "2x6" to "4x3", "3x5" to "4x4", "2x8" to "4x4", "1+4" to "1+5", "1+6" to "1+5",
    )

    /**
     * Maps any known id (current or legacy, of either orientation) to a layout that exists for
     * [landscape]: itself when it does, otherwise the layout of similar size. Null for unknown ids.
     */
    fun resolve(id: String?, landscape: Boolean): String? {
        val current = legacy[id] ?: id ?: return null
        if (forOrientation(landscape).any { it.id == current }) return current
        if (forOrientation(!landscape).none { it.id == current }) return null
        return (if (landscape) landscapeOf else portraitOf)[current]
    }

    fun spec(id: String, landscape: Boolean): GridSpec {
        val current = resolve(id, landscape) ?: defaultId(landscape)
        if (!current.startsWith("1+")) return current.split("x").let { uniform(it[0].toInt(), it[1].toInt()) }
        val small = current.substring(2).toInt()
        return when {
            !landscape -> stackedBig(small)
            small == 5 -> cornerBig(3, 3, 2)
            small == 7 -> cornerBig(4, 4, 3)
            else -> cornerBig(4, 3, 2)
        }
    }

    private fun grid(cols: Int, rows: Int) = LayoutPreset("${cols}x$rows", cols * rows, "$cols x $rows")

    private fun big(small: Int) = LayoutPreset("1+$small", small + 1, "1 big + $small")

    private fun uniform(cols: Int, rows: Int) =
        GridSpec(cols, rows, (0 until rows).flatMap { r -> (0 until cols).map { c -> Cell(c, r) } })

    /** Landscape: a [big]x[big] cell in the top-left corner, every other cell single-sized, row-major. */
    private fun cornerBig(cols: Int, rows: Int, big: Int): GridSpec {
        val cells = mutableListOf(Cell(0, 0, big, big))
        for (r in 0 until rows) for (c in 0 until cols) {
            if (r < big && c < big) continue
            cells += Cell(c, r)
        }
        return GridSpec(cols, rows, cells)
    }

    /** Portrait: one full-width cell two rows tall on top, the [small] others (an even number) two per row below it. */
    private fun stackedBig(small: Int): GridSpec {
        val cells = mutableListOf(Cell(0, 0, 2, 2))
        for (i in 0 until small) cells += Cell(i % 2, 2 + i / 2)
        return GridSpec(2, 2 + small / 2, cells)
    }
}
