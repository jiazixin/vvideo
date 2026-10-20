/*
 * Album layout ported from Telegram for Android: MessageObject.GroupedMessages.calculate()
 * and GroupedMessagePosition (https://github.com/DrKLO/Telegram, master 0aae959806c5).
 * Telegram for Android is licensed under GNU GPL v. 2 or later;
 * Copyright Nikolai Kudashov, 2013-2018.
 *
 * Translated to Kotlin for VVideo, which is licensed under GNU GPL v. 3 or later.
 * Only the photo/video album case of an incoming channel post is kept: document and
 * music groups, captions, outgoing bubbles, avatars and the share button are not
 * part of a VVideo album. The arithmetic, constants and rounding are unchanged.
 */
package dev.vvideo.feature.library.album

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

const val POSITION_FLAG_LEFT = 1
const val POSITION_FLAG_RIGHT = 2
const val POSITION_FLAG_TOP = 4
const val POSITION_FLAG_BOTTOM = 8

/**
 * One item's cell. Columns and rows are [minX]..[maxX] and [minY]..[maxY]. [pw] is the
 * width in units of [TelegramGroupedLayout.MAX_SIZE_WIDTH]; [ph] the height as a
 * fraction of [TelegramGroupedLayout.MAX_SIZE_HEIGHT]. A cell spanning several rows
 * carries the heights of the rows beside it in [siblingHeights]; a cell to the right
 * of such a cell starts [leftSpanOffset] width units from the left.
 */
class GroupedPosition(val aspectRatio: Float) {
    var minX = 0
    var maxX = 0
    var minY = 0
    var maxY = 0
    var pw = 0
    var ph = 0f
    var flags = 0
    var spanSize = 0
    var leftSpanOffset = 0
    var siblingHeights: FloatArray? = null

    fun set(minX: Int, maxX: Int, minY: Int, maxY: Int, w: Int, h: Float, flags: Int) {
        this.minX = minX
        this.maxX = maxX
        this.minY = minY
        this.maxY = maxY
        this.pw = w
        this.spanSize = w
        this.ph = h
        this.flags = flags
    }
}

object TelegramGroupedLayout {
    const val MAX_SIZE_WIDTH = 800
    const val MAX_SIZE_HEIGHT = 814.0f

    private class Attempt(val lineCounts: IntArray, val heights: FloatArray)

    /**
     * Positions for items with the given width/height [ratios] (at most ten).
     * [density] and [minDisplaySide] (in px) stand in for Telegram's AndroidUtilities:
     * several of its limits are dp values, so the result depends on the device.
     */
    fun calculate(
        ratios: List<Float>,
        density: Float,
        minDisplaySide: Float
    ): List<GroupedPosition> {
        require(ratios.size <= 10)
        require(minDisplaySide > 0f && density > 0f)
        val count = ratios.size
        if (count == 0) return emptyList()
        fun dp(value: Float): Int = if (value == 0f) 0 else ceil(density * value).toInt()

        val maxSizeWidth = MAX_SIZE_WIDTH
        val maxSizeHeight = MAX_SIZE_HEIGHT
        val proportions = StringBuilder()
        var averageAspectRatio = 1.0f
        var forceCalc = false
        val posArray = ratios.map { ratio ->
            val position = GroupedPosition(if (ratio.isFinite() && ratio > 0f) ratio else 1.0f)
            when {
                position.aspectRatio > 1.2f -> proportions.append("w")
                position.aspectRatio < 0.8f -> proportions.append("n")
                else -> proportions.append("q")
            }
            averageAspectRatio += position.aspectRatio
            if (position.aspectRatio > 2.0f) forceCalc = true
            position
        }

        val minHeight = dp(120f)
        val minWidth = (dp(120f) / (minDisplaySide / maxSizeWidth.toFloat())).toInt()
        val paddingsWidth = (dp(40f) / (minDisplaySide / maxSizeWidth.toFloat())).toInt()
        val maxAspectRatio = maxSizeWidth / maxSizeHeight
        averageAspectRatio /= count
        val minH = dp(100f) / maxSizeHeight

        if (count == 1) {
            // Telegram draws a single item as a plain message; this is the grouped
            // branch it keeps for one item.
            val position1 = posArray[0]
            val height = Math.round(
                min(
                    maxSizeWidth / position1.aspectRatio,
                    min(maxSizeWidth / position1.aspectRatio, maxSizeHeight / 2.0f)
                )
            ) / maxSizeHeight
            position1.set(
                0, 0, 0, 0, maxSizeWidth, height,
                POSITION_FLAG_LEFT or POSITION_FLAG_RIGHT or POSITION_FLAG_TOP or POSITION_FLAG_BOTTOM
            )
        } else if (!forceCalc && (count == 2 || count == 3 || count == 4)) {
            if (count == 2) {
                val position1 = posArray[0]
                val position2 = posArray[1]
                val pString = proportions.toString()
                if (pString == "ww" && averageAspectRatio > 1.4 * maxAspectRatio &&
                    position1.aspectRatio - position2.aspectRatio < 0.2
                ) {
                    val height = Math.round(
                        min(
                            maxSizeWidth / position1.aspectRatio,
                            min(maxSizeWidth / position2.aspectRatio, maxSizeHeight / 2.0f)
                        )
                    ) / maxSizeHeight
                    position1.set(
                        0,
                        0,
                        0,
                        0,
                        maxSizeWidth,
                        height,
                        POSITION_FLAG_LEFT or POSITION_FLAG_RIGHT or POSITION_FLAG_TOP
                    )
                    position2.set(
                        0,
                        0,
                        1,
                        1,
                        maxSizeWidth,
                        height,
                        POSITION_FLAG_LEFT or POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM
                    )
                } else if (pString == "ww" || pString == "qq") {
                    val width = maxSizeWidth / 2
                    val height = Math.round(
                        min(
                            width / position1.aspectRatio,
                            min(width / position2.aspectRatio, maxSizeHeight)
                        )
                    ) / maxSizeHeight
                    position1.set(
                        0,
                        0,
                        0,
                        0,
                        width,
                        height,
                        POSITION_FLAG_LEFT or POSITION_FLAG_BOTTOM or POSITION_FLAG_TOP
                    )
                    position2.set(
                        1,
                        1,
                        0,
                        0,
                        width,
                        height,
                        POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM or POSITION_FLAG_TOP
                    )
                } else {
                    var secondWidth = max(
                        0.4f * maxSizeWidth,
                        Math.round(maxSizeWidth / position1.aspectRatio / (1.0f / position1.aspectRatio + 1.0f / position2.aspectRatio))
                            .toFloat()
                    ).toInt()
                    var firstWidth = maxSizeWidth - secondWidth
                    if (firstWidth < minWidth) {
                        val diff = minWidth - firstWidth
                        firstWidth = minWidth
                        secondWidth -= diff
                    }
                    val height = min(
                        maxSizeHeight,
                        Math.round(
                            min(
                                firstWidth / position1.aspectRatio,
                                secondWidth / position2.aspectRatio
                            )
                        ).toFloat()
                    ) / maxSizeHeight
                    position1.set(
                        0,
                        0,
                        0,
                        0,
                        firstWidth,
                        height,
                        POSITION_FLAG_LEFT or POSITION_FLAG_BOTTOM or POSITION_FLAG_TOP
                    )
                    position2.set(
                        1,
                        1,
                        0,
                        0,
                        secondWidth,
                        height,
                        POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM or POSITION_FLAG_TOP
                    )
                }
            } else if (count == 3) {
                val position1 = posArray[0]
                val position2 = posArray[1]
                val position3 = posArray[2]
                if (proportions[0] == 'n') {
                    val thirdHeight = min(
                        maxSizeHeight * 0.5f,
                        Math.round(position2.aspectRatio * maxSizeWidth / (position3.aspectRatio + position2.aspectRatio))
                            .toFloat()
                    )
                    val secondHeight = maxSizeHeight - thirdHeight
                    val rightWidth = max(
                        minWidth.toFloat(),
                        min(
                            maxSizeWidth * 0.5f,
                            Math.round(
                                min(
                                    thirdHeight * position3.aspectRatio,
                                    secondHeight * position2.aspectRatio
                                )
                            ).toFloat()
                        )
                    ).toInt()
                    val leftWidth = Math.round(
                        min(
                            maxSizeHeight * position1.aspectRatio + paddingsWidth,
                            (maxSizeWidth - rightWidth).toFloat()
                        )
                    )
                    position1.set(
                        0,
                        0,
                        0,
                        1,
                        leftWidth,
                        1.0f,
                        POSITION_FLAG_LEFT or POSITION_FLAG_BOTTOM or POSITION_FLAG_TOP
                    )
                    position2.set(
                        1,
                        1,
                        0,
                        0,
                        rightWidth,
                        secondHeight / maxSizeHeight,
                        POSITION_FLAG_RIGHT or POSITION_FLAG_TOP
                    )
                    position3.set(
                        0,
                        1,
                        1,
                        1,
                        rightWidth,
                        thirdHeight / maxSizeHeight,
                        POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM
                    )
                    position3.spanSize = maxSizeWidth
                    position1.siblingHeights =
                        floatArrayOf(thirdHeight / maxSizeHeight, secondHeight / maxSizeHeight)
                    position2.spanSize = maxSizeWidth - leftWidth
                    position3.leftSpanOffset = leftWidth
                } else {
                    val firstHeight = Math.round(
                        min(
                            maxSizeWidth / position1.aspectRatio,
                            maxSizeHeight * 0.66f
                        )
                    ) / maxSizeHeight
                    position1.set(
                        0,
                        1,
                        0,
                        0,
                        maxSizeWidth,
                        firstHeight,
                        POSITION_FLAG_LEFT or POSITION_FLAG_RIGHT or POSITION_FLAG_TOP
                    )
                    val width = maxSizeWidth / 2
                    // Telegram subtracts the fraction firstHeight from maxSizeHeight here; kept as is.
                    var secondHeight = min(
                        maxSizeHeight - firstHeight,
                        Math.round(
                            min(
                                width / position2.aspectRatio,
                                width / position3.aspectRatio
                            )
                        ).toFloat()
                    ) / maxSizeHeight
                    if (secondHeight < minH) secondHeight = minH
                    position2.set(
                        0,
                        0,
                        1,
                        1,
                        width,
                        secondHeight,
                        POSITION_FLAG_LEFT or POSITION_FLAG_BOTTOM
                    )
                    position3.set(
                        1,
                        1,
                        1,
                        1,
                        width,
                        secondHeight,
                        POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM
                    )
                }
            } else {
                val position1 = posArray[0]
                val position2 = posArray[1]
                val position3 = posArray[2]
                val position4 = posArray[3]
                if (proportions[0] == 'w') {
                    val h0 = Math.round(
                        min(
                            maxSizeWidth / position1.aspectRatio,
                            maxSizeHeight * 0.66f
                        )
                    ) / maxSizeHeight
                    position1.set(
                        0,
                        2,
                        0,
                        0,
                        maxSizeWidth,
                        h0,
                        POSITION_FLAG_LEFT or POSITION_FLAG_RIGHT or POSITION_FLAG_TOP
                    )
                    var h =
                        Math.round(maxSizeWidth / (position2.aspectRatio + position3.aspectRatio + position4.aspectRatio))
                            .toFloat()
                    var w0 = max(
                        minWidth.toFloat(),
                        min(maxSizeWidth * 0.4f, h * position2.aspectRatio)
                    ).toInt()
                    var w2 = max(
                        max(minWidth.toFloat(), maxSizeWidth * 0.33f),
                        h * position4.aspectRatio
                    ).toInt()
                    var w1 = maxSizeWidth - w0 - w2
                    if (w1 < dp(58f)) {
                        val diff = dp(58f) - w1
                        w1 = dp(58f)
                        w0 -= diff / 2
                        w2 -= (diff - diff / 2)
                    }
                    h = min(maxSizeHeight - h0, h)
                    h /= maxSizeHeight
                    if (h < minH) h = minH
                    position2.set(0, 0, 1, 1, w0, h, POSITION_FLAG_LEFT or POSITION_FLAG_BOTTOM)
                    position3.set(1, 1, 1, 1, w1, h, POSITION_FLAG_BOTTOM)
                    position4.set(2, 2, 1, 1, w2, h, POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM)
                } else {
                    val w = max(
                        minWidth,
                        Math.round(maxSizeHeight / (1.0f / position2.aspectRatio + 1.0f / position3.aspectRatio + 1.0f / position4.aspectRatio))
                    )
                    val h0 = min(
                        0.33f,
                        max(minHeight.toFloat(), w / position2.aspectRatio) / maxSizeHeight
                    )
                    val h1 = min(
                        0.33f,
                        max(minHeight.toFloat(), w / position3.aspectRatio) / maxSizeHeight
                    )
                    val h2 = 1.0f - h0 - h1
                    val w0 = Math.round(
                        min(
                            maxSizeHeight * position1.aspectRatio + paddingsWidth,
                            (maxSizeWidth - w).toFloat()
                        )
                    )
                    position1.set(
                        0,
                        0,
                        0,
                        2,
                        w0,
                        h0 + h1 + h2,
                        POSITION_FLAG_LEFT or POSITION_FLAG_TOP or POSITION_FLAG_BOTTOM
                    )
                    position2.set(1, 1, 0, 0, w, h0, POSITION_FLAG_RIGHT or POSITION_FLAG_TOP)
                    position3.set(0, 1, 1, 1, w, h1, POSITION_FLAG_RIGHT)
                    position3.spanSize = maxSizeWidth
                    position4.set(0, 1, 2, 2, w, h2, POSITION_FLAG_RIGHT or POSITION_FLAG_BOTTOM)
                    position4.spanSize = maxSizeWidth
                    position2.spanSize = maxSizeWidth - w0
                    position3.leftSpanOffset = w0
                    position4.leftSpanOffset = w0
                    position1.siblingHeights = floatArrayOf(h0, h1, h2)
                }
            }
        } else {
            val croppedRatios = FloatArray(count) { a ->
                val ratio = if (averageAspectRatio > 1.1f) {
                    max(1.0f, posArray[a].aspectRatio)
                } else {
                    min(1.0f, posArray[a].aspectRatio)
                }
                max(0.66667f, min(1.7f, ratio))
            }

            fun multiHeight(start: Int, end: Int): Float {
                var sum = 0f
                for (a in start until end) sum += croppedRatios[a]
                return maxSizeWidth / sum
            }

            val attempts = ArrayList<Attempt>()
            for (firstLine in 1 until count) {
                val secondLine = count - firstLine
                if (firstLine > 3 || secondLine > 3) continue
                attempts += Attempt(
                    intArrayOf(firstLine, secondLine),
                    floatArrayOf(multiHeight(0, firstLine), multiHeight(firstLine, count))
                )
            }
            for (firstLine in 1 until count - 1) {
                for (secondLine in 1 until count - firstLine) {
                    val thirdLine = count - firstLine - secondLine
                    if (firstLine > 3 || secondLine > (if (averageAspectRatio < 0.85f) 4 else 3) || thirdLine > 3) continue
                    attempts += Attempt(
                        intArrayOf(firstLine, secondLine, thirdLine),
                        floatArrayOf(
                            multiHeight(0, firstLine),
                            multiHeight(firstLine, firstLine + secondLine),
                            multiHeight(firstLine + secondLine, count)
                        )
                    )
                }
            }
            for (firstLine in 1 until count - 2) {
                for (secondLine in 1 until count - firstLine) {
                    for (thirdLine in 1 until count - firstLine - secondLine) {
                        val fourthLine = count - firstLine - secondLine - thirdLine
                        if (firstLine > 3 || secondLine > 3 || thirdLine > 3 || fourthLine > 3) continue
                        attempts += Attempt(
                            intArrayOf(firstLine, secondLine, thirdLine, fourthLine),
                            floatArrayOf(
                                multiHeight(0, firstLine),
                                multiHeight(firstLine, firstLine + secondLine),
                                multiHeight(
                                    firstLine + secondLine,
                                    firstLine + secondLine + thirdLine
                                ),
                                multiHeight(firstLine + secondLine + thirdLine, count)
                            )
                        )
                    }
                }
            }

            var optimal: Attempt? = null
            var optimalDiff = 0.0f
            val maxHeight = (maxSizeWidth / 3 * 4).toFloat()
            for (attempt in attempts) {
                var height = 0f
                var minLineHeight = Float.MAX_VALUE
                for (lineHeight in attempt.heights) {
                    height += lineHeight
                    if (lineHeight < minLineHeight) minLineHeight = lineHeight
                }
                var diff = abs(height - maxHeight)
                val lines = attempt.lineCounts
                if (lines.size > 1) {
                    if (lines[0] > lines[1] || (lines.size > 2 && lines[1] > lines[2]) || (lines.size > 3 && lines[2] > lines[3])) {
                        diff *= 1.2f
                    }
                }
                if (minLineHeight < minWidth) diff *= 1.5f
                if (optimal == null || diff < optimalDiff) {
                    optimal = attempt
                    optimalDiff = diff
                }
            }
            if (optimal == null) return emptyList()

            var index = 0
            for (i in optimal.lineCounts.indices) {
                val c = optimal.lineCounts[i]
                val lineHeight = optimal.heights[i]
                var spanLeft = maxSizeWidth
                var posToFix: GroupedPosition? = null
                for (k in 0 until c) {
                    val ratio = croppedRatios[index]
                    val width = (ratio * lineHeight).toInt()
                    spanLeft -= width
                    val pos = posArray[index]
                    var flags = 0
                    if (i == 0) flags = flags or POSITION_FLAG_TOP
                    if (i == optimal.lineCounts.size - 1) flags = flags or POSITION_FLAG_BOTTOM
                    if (k == 0) flags = flags or POSITION_FLAG_LEFT
                    if (k == c - 1) {
                        flags = flags or POSITION_FLAG_RIGHT
                        posToFix = pos
                    }
                    pos.set(k, k, i, i, width, max(minH, lineHeight / maxSizeHeight), flags)
                    index++
                }
                posToFix!!.pw += spanLeft
                posToFix.spanSize += spanLeft
            }
        }
        return posArray
    }
}
