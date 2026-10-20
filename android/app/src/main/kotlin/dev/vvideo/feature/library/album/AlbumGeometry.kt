/*
 * Cell sizes follow how Telegram for Android's ChatMessageCell draws grouped media
 * (https://github.com/DrKLO/Telegram, master 0aae959806c5; GNU GPL v. 2 or later,
 * Copyright Nikolai Kudashov, 2013-2018): a cell is pw/1000 of the screen width wide
 * and ph of half the screen height tall, later rows are widened to end where the first
 * row ends, and a grouped photo keeps a 4 dp radius on inner corners.
 * VVideo then scales the whole album to its own width. GNU GPL v. 3 or later.
 */
package dev.vvideo.feature.library.album

import kotlin.math.ceil
import kotlin.math.max

/** One item's rectangle in px, inside an album that starts at 0,0. */
data class AlbumCell(
    val index: Int,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** POSITION_FLAG_* bits: which album edges this cell touches. */
    val flags: Int
) {
    val atLeft get() = (flags and POSITION_FLAG_LEFT) != 0
    val atRight get() = (flags and POSITION_FLAG_RIGHT) != 0
    val atTop get() = (flags and POSITION_FLAG_TOP) != 0
    val atBottom get() = (flags and POSITION_FLAG_BOTTOM) != 0
}

/** An album's cells and its size, in px. */
data class AlbumGeometry(val cells: List<AlbumCell>, val width: Float, val height: Float)

object AlbumGeometryCalculator {
    /**
     * Places [positions] the way Telegram does on a [screenWidth]×[screenHeight] px
     * screen, scales the album to [albumWidth] px and leaves [gap] px between cells.
     */
    fun geometry(
        positions: List<GroupedPosition>,
        screenWidth: Float,
        screenHeight: Float,
        albumWidth: Float,
        gap: Float
    ): AlbumGeometry {
        if (positions.isEmpty() || albumWidth <= 0f) return AlbumGeometry(
            emptyList(),
            albumWidth,
            0f
        )
        val dWidth = screenWidth
        val maxHeight = max(screenWidth, screenHeight) * 0.5f
        fun units(value: Int) = ceil(value / 1000.0f * dWidth)

        // Widths as ChatMessageCell computes them for an incoming group.
        val widths = positions.map { position ->
            var w = units(position.pw)
            if (position.minY != 0 && (position.flags and POSITION_FLAG_RIGHT) != 0) {
                var firstLineWidth = 0f
                var currentLineWidth = 0f
                for (other in positions) {
                    val span =
                        units(other.pw) + if (other.leftSpanOffset != 0) units(other.leftSpanOffset) else 0f
                    when {
                        other.minY == 0 -> firstLineWidth += span
                        other.minY == position.minY -> currentLineWidth += span
                        other.minY > position.minY -> break
                    }
                }
                w += firstLineWidth - currentLineWidth
            }
            w
        }
        // A row is as tall as its single-row cells; a cell spanning rows covers them.
        val rows = positions.maxOf { it.maxY } + 1
        val rowHeights = FloatArray(rows)
        for (position in positions) {
            if (position.minY == position.maxY) {
                rowHeights[position.minY] =
                    max(rowHeights[position.minY], ceil(maxHeight * position.ph))
            }
        }
        val rowTops = FloatArray(rows)
        for (r in 1 until rows) rowTops[r] = rowTops[r - 1] + rowHeights[r - 1]
        val telegramHeight = rowTops[rows - 1] + rowHeights[rows - 1]

        val xs = FloatArray(positions.size)
        positions.forEachIndexed { i, position ->
            var x = if (position.leftSpanOffset != 0) units(position.leftSpanOffset) else 0f
            for (j in 0 until i) if (positions[j].minY == position.minY) x += widths[j]
            xs[i] = x
        }
        val telegramWidth = positions.indices.maxOf { xs[it] + widths[it] }

        val scale = albumWidth / telegramWidth
        val height = telegramHeight * scale
        val cells = positions.mapIndexed { i, position ->
            val x = xs[i] * scale
            val y = rowTops[position.minY] * scale
            val right =
                if ((position.flags and POSITION_FLAG_RIGHT) != 0) albumWidth else (xs[i] + widths[i]) * scale - gap
            val bottom = if ((position.flags and POSITION_FLAG_BOTTOM) != 0) {
                height
            } else {
                (rowTops[position.maxY] + rowHeights[position.maxY]) * scale - gap
            }
            AlbumCell(i, x, y, max(0f, right - x), max(0f, bottom - y), position.flags)
        }
        return AlbumGeometry(cells, albumWidth, height)
    }
}
