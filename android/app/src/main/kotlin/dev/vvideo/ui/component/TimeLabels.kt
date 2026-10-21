package dev.vvideo.ui.component

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Formatting inputs come from resources; clock and zone are injectable for boundary tests. */
data class TimeLabels(
    val today: String, val yesterday: String, val datePattern: String,
    val yearPattern: String, val recentPattern: String, val oldPattern: String,
    val weekdayPattern: String, val locale: Locale = Locale.getDefault(),
) {
    private fun format(value: ZonedDateTime, pattern: String) =
        value.format(DateTimeFormatter.ofPattern(pattern, locale))

    fun recent(
        millis: Long,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val value = Instant.ofEpochMilli(millis).atZone(zone)
        val date = value.toLocalDate()
        val current = now.atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(date, current)
        return when {
            days == 0L -> format(value, "HH:mm")
            days == 1L -> yesterday
            days in 2..6 -> format(value, weekdayPattern)
            date.year == current.year -> format(value, recentPattern)
            else -> format(value, oldPattern)
        }
    }

    fun date(
        nanos: Long,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val value = instant(nanos).atZone(zone)
        val current = now.atZone(zone).toLocalDate()
        return when (value.toLocalDate()) {
            current -> today
            current.minusDays(1) -> yesterday
            else -> format(value, if (value.year == current.year) datePattern else yearPattern)
        }
    }

    companion object {
        fun instant(nanos: Long): Instant = Instant.ofEpochSecond(
            Math.floorDiv(nanos, 1_000_000_000L),
            Math.floorMod(nanos, 1_000_000_000L)
        )

        fun timestamp(
            nanos: Long,
            pattern: String,
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault()
        ): String = instant(nanos).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale))
    }
}

