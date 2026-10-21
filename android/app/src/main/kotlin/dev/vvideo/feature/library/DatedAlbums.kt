package dev.vvideo.feature.library

import dev.vvideo.model.Sort
import dev.vvideo.model.SortField
import dev.vvideo.ui.component.TimeLabels
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Gives each album the day of its first item: [Album.dayLabel] always, and
 * [Album.dateLabel] only where the day differs from the album before. A name
 * sort, or no [labels], shows no dates.
 */
fun datedAlbums(
    albums: List<Album>,
    sort: Sort,
    labels: TimeLabels?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<Album> {
    var previous: LocalDate? = null
    return albums.map { album ->
        if (sort.field == SortField.Name || labels == null) {
            album.copy(dateLabel = null, dayLabel = null)
        } else {
            val first = album.items.first()
            val time = if (sort.field == SortField.Modified) first.mtime else first.effectiveTime
            val date = TimeLabels.instant(time).atZone(zone).toLocalDate()
            val label = labels.date(time, now, zone)
            album.copy(dateLabel = if (date != previous) label else null, dayLabel = label)
                .also { previous = date }
        }
    }
}
