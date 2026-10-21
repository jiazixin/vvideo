package dev.vvideo.model

import org.junit.Assert.assertEquals
import org.junit.Test

class WireValuesTest {
    @Test
    fun knownServerValuesMapToTheirTypes() {
        assertEquals(MediaKind.Video, MediaKind.fromWire("video"))
        assertEquals(MediaKind.Image, MediaKind.fromWire("image"))
        assertEquals(MediaStatus.Ready, MediaStatus.fromWire("ready"))
        assertEquals(MediaStatus.Failed, MediaStatus.fromWire("failed"))
        assertEquals(CaptureSource.ExifOriginal, CaptureSource.fromWire("exif_datetime_original"))
        assertEquals(
            CaptureSource.ContainerCreation,
            CaptureSource.fromWire("container_creation_time")
        )
        assertEquals(CaptureSource.Modified, CaptureSource.fromWire("mtime"))
        assertEquals(SortField.Modified, SortField.fromWire("mtime"))
        assertEquals(SortDirection.Descending, SortDirection.fromWire("desc"))
        assertEquals(ThemeMode.Dark, ThemeMode.fromKey("dark"))
    }

    // Unknown values keep the client's earlier behaviour: an unknown kind opens like a
    // photo, an unknown status still waits for its cover, and stored sorts fall back.
    @Test
    fun unknownValuesFallBack() {
        assertEquals(MediaKind.Image, MediaKind.fromWire("audio"))
        assertEquals(MediaStatus.Pending, MediaStatus.fromWire("unsupported"))
        assertEquals(CaptureSource.Unknown, CaptureSource.fromWire(""))
        assertEquals(SortField.Capture, SortField.fromWire("size"))
        assertEquals(SortDirection.Ascending, SortDirection.fromWire(""))
        assertEquals(ThemeMode.System, ThemeMode.fromKey(null))
    }

    @Test
    fun wireValuesRoundTrip() {
        SortField.entries.forEach { assertEquals(it, SortField.fromWire(it.wire)) }
        SortDirection.entries.forEach { assertEquals(it, SortDirection.fromWire(it.wire)) }
        MediaKind.entries.forEach { assertEquals(it, MediaKind.fromWire(it.wire)) }
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
    }
}
