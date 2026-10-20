package dev.vvideo.feature.library.album

import kotlin.math.pow

data class AlbumRow(val indices: List<Int>, val weights: List<Float>, val heightRatio: Float)

object AlbumLayoutCalculator {
    // Dynamic programming considers at most ten items and four cells in a row.
    // Returned geometry depends only on stable metadata, never on image arrival.
    fun calculate(
        ratios: List<Float>,
        widthDp: Float = 0f,
        minimumWidths: List<Float> = emptyList()
    ): List<AlbumRow> {
        require(ratios.size <= 10)
        if (ratios.isEmpty()) return emptyList()
        val values = ratios.map { if (it.isFinite()) it.coerceIn(.3f, 3.3f) else 1f }
        val cost = DoubleArray(values.size + 1) { Double.POSITIVE_INFINITY }
        val next = IntArray(values.size)
        cost[values.size] = 0.0
        for (i in values.indices.reversed()) for (count in 1..minOf(4, values.size - i)) {
            val height = 1f / values.subList(i, i + count).sum()
            if (count > 1 && widthDp > 0 && (i until i + count).any { j ->
                    (widthDp - 2 * (count - 1)) * values[j] * height < maxOf(
                        48f,
                        minimumWidths.getOrElse(j) { 48f })
                }) continue
            val candidate = (height - .32f).toDouble()
                .pow(2) + cost[i + count] + if (count == 1 && values[i] < 1f) .2 else 0.0
            if (candidate < cost[i]) {
                cost[i] = candidate
                next[i] = count
            }
        }
        val out = mutableListOf<AlbumRow>()
        var index = 0
        while (index < values.size) {
            val count = next[index]
            val indices = (index until index + count).toList()
            val sum = indices.sumOf { values[it].toDouble() }.toFloat()
            out += AlbumRow(indices, indices.map { values[it] / sum }, 1f / sum)
            index += count
        }
        return out
    }
}
