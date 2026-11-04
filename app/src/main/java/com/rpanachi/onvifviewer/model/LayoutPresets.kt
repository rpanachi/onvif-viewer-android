package com.rpanachi.onvifviewer.model

data class Cell(val col: Int, val row: Int, val colSpan: Int = 1, val rowSpan: Int = 1)

data class GridSpec(val cols: Int, val rows: Int, val cells: List<Cell>) {
    val count: Int get() = cells.size
}

data class LayoutPreset(val id: String, val label: String, val count: Int)

object LayoutPresets {
    val all = listOf(
        LayoutPreset("1", "1 camera", 1),
        LayoutPreset("4", "4 cameras (2 x 2)", 4),
        LayoutPreset("6", "6 cameras (1 big + 5)", 6),
        LayoutPreset("8", "8 cameras (1 big + 7)", 8),
        LayoutPreset("9", "9 cameras (3 x 3)", 9),
        LayoutPreset("9b", "9 cameras (1 big + 8)", 9),
        LayoutPreset("12", "12 cameras (4 x 3)", 12),
        LayoutPreset("16", "16 cameras (4 x 4)", 16),
    )

    fun byId(id: String): LayoutPreset = all.firstOrNull { it.id == id } ?: all[1]

    fun spec(id: String, landscape: Boolean): GridSpec = when (id) {
        "1" -> uniform(1, 1)
        "4" -> uniform(2, 2)
        "6" -> bigPlusSmall(3, 3, 2)
        "8" -> bigPlusSmall(4, 4, 3)
        "9" -> uniform(3, 3)
        "9b" -> if (landscape) bigPlusSmall(4, 3, 2) else bigPlusSmall(3, 4, 2)
        "12" -> if (landscape) uniform(4, 3) else uniform(3, 4)
        "16" -> uniform(4, 4)
        else -> uniform(2, 2)
    }

    private fun uniform(cols: Int, rows: Int) =
        GridSpec(cols, rows, (0 until rows).flatMap { r -> (0 until cols).map { c -> Cell(c, r) } })

    /** A [big]x[big] cell in the top-left corner, every other cell single-sized, row-major. */
    private fun bigPlusSmall(cols: Int, rows: Int, big: Int): GridSpec {
        val cells = mutableListOf(Cell(0, 0, big, big))
        for (r in 0 until rows) for (c in 0 until cols) {
            if (r < big && c < big) continue
            cells += Cell(c, r)
        }
        return GridSpec(cols, rows, cells)
    }
}
