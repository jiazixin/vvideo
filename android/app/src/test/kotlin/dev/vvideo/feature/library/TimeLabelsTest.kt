package dev.vvideo.feature.library

import dev.vvideo.model.*
import dev.vvideo.ui.component.TimeLabels
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class TimeLabelsTest {
    private val labels = TimeLabels(
        "今天",
        "昨天",
        "M月d日 EEE",
        "yyyy年M月d日 EEE",
        "M月d日",
        "yyyy/M/d",
        "EEE",
        Locale.CHINA
    )
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-10-10T08:00:00Z")
    private fun instant(date: String) = LocalDateTime.parse(date).atZone(zone).toInstant()
    private fun ns(date: String) = instant(date).epochSecond * 1_000_000_000L
    @Test
    fun allRecentColumns() {
        listOf(
            "2026-10-10T14:22:00" to "14:22",
            "2026-10-09T12:00:00" to "昨天",
            "2026-10-07T12:00:00" to "周三",
            "2026-10-03T12:00:00" to "10月3日",
            "2023-10-03T12:00:00" to "2023/10/3"
        ).forEach { (input, expected) ->
            assertEquals(
                expected,
                labels.recent(instant(input).toEpochMilli(), now, zone)
            )
        }
    }

    @Test
    fun allDateColumns() {
        listOf(
            "2026-10-10T14:22:00" to "今天",
            "2026-10-09T12:00:00" to "昨天",
            "2026-10-07T12:00:00" to "10月7日 周三",
            "2026-04-06T12:00:00" to "4月6日 周一",
            "2024-04-06T12:00:00" to "2024年4月6日 周六"
        ).forEach { (input, expected) -> assertEquals(expected, labels.date(ns(input), now, zone)) }
    }

    @Test
    fun viewerAndDetailEveryAge() {
        listOf(
            "2026-10-10T14:22:15",
            "2026-10-09T12:00:15",
            "2026-10-07T12:00:15",
            "2026-04-06T12:00:15",
            "2024-04-06T12:00:15"
        ).forEach { input ->
            val date = LocalDateTime.parse(input)
            val expected =
                "${date.year}年${date.monthValue}月${date.dayOfMonth}日 " + input.substring(11, 16)
            assertEquals(
                expected,
                TimeLabels.timestamp(ns(input), "yyyy年M月d日 HH:mm", zone, Locale.CHINA)
            )
            assertEquals(
                expected + ":15",
                TimeLabels.timestamp(ns(input), "yyyy年M月d日 HH:mm:ss", zone, Locale.CHINA)
            )
        }
    }

    @Test
    fun deviceTimezoneAndNegativeNanos() {
        assertEquals("昨天", labels.date(ns("2026-10-09T23:59:59"), now, zone))
        assertEquals(Instant.ofEpochSecond(-1, 999999999), TimeLabels.instant(-1))
    }

    private fun album(id: String, date: String) = Album(
        listOf(
            Media(
                id,
                "v",
                "l",
                1,
                id,
                "image",
                100,
                100,
                0,
                0,
                1,
                ns(date),
                ns(date),
                "ready",
                "",
                ""
            )
        )
    )

    @Test
    fun datesRecomputedAcrossWindowBoundaries() {
        val a = album("a", "2026-10-10T14:00:00")
        val b = album("b", "2026-10-10T13:00:00")
        val c = album("c", "2026-10-09T12:00:00")
        val result = datedAlbums(listOf(a, b, c), Sort(), labels, now, zone)
        assertEquals(listOf("今天", null, "昨天"), result.map { it.dateLabel })
        assertEquals(
            listOf("今天", null),
            datedAlbums(listOf(a, b), Sort(), labels, now, zone).map { it.dateLabel })
        assertEquals("今天", datedAlbums(listOf(b, c), Sort(), labels, now, zone).first().dateLabel)
        assertTrue(
            datedAlbums(
                result,
                Sort("name"),
                labels,
                now,
                zone
            ).all { it.dateLabel == null && it.dayLabel == null })
    }

    @Test
    fun keysRestoreMediaInsideAlbumAndAfterPrepend() {
        val a = album("a", "2026-10-10T14:00:00")
        val b = album("b", "2026-10-09T14:00:00")
        val albums = datedAlbums(listOf(a, b), Sort(), labels, now, zone)
        val state =
            LibraryUiState(albums = albums, children = listOf(Directory(4, "l", 1, "child", 1)))
        val keys = libraryKeys(state)
        assertEquals(listOf("subfolder-bar", "date:a", "album:a", "date:b", "album:b"), keys)
        assertEquals(4, restoreIndex(keys, albums, "missing", "b"))
        assertEquals(2, restoreIndex(keys, albums, "album:a", "b"))
        assertEquals(0, restoreIndex(keys, albums, "gone", "gone"))
        assertEquals("album:a", anchorKey(listOf(a.copy(items = a.items + b.items)), "b"))
    }

    @Test
    fun modificationSortUsesModificationDayAndPrependedKeysStayStable() {
        val original = album("a", "2026-10-10T14:00:00")
        val modified =
            original.copy(items = original.items.map { it.copy(mtime = ns("2026-10-09T08:20:00")) })
        val result = datedAlbums(listOf(modified), Sort("mtime"), labels, now, zone).single()
        assertEquals("昨天", result.dateLabel)
        val b = album("b", "2026-10-09T14:00:00")
        val prepended = datedAlbums(
            listOf(album("new", "2026-10-11T14:00:00"), original, b),
            Sort(),
            labels,
            now,
            zone
        )
        val keys = libraryKeys(LibraryUiState(albums = prepended))
        assertEquals(5, restoreIndex(keys, prepended, "album:b", "b"))
        val folders = libraryKeys(
            LibraryUiState(
                children = listOf(
                    Directory(1, "l", 0, "one", 1),
                    Directory(2, "l", 0, "two", 1)
                )
            )
        )
        assertEquals(listOf("folder:1", "folder:2"), folders)
        assertEquals(1, restoreIndex(folders, emptyList(), "folder:2", ""))
    }

}
