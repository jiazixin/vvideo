package dev.vvideo.feature.library

import dev.vvideo.ui.component.TimeLabels
import java.time.*

fun datedAlbums(
    albums: List<Album>,
    sort: dev.vvideo.model.Sort,
    labels: TimeLabels?,
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault()
): List<Album> {
    var previous: LocalDate? = null
    return albums.map { album ->
        if (sort.field == "name" || labels == null) album.copy(dateLabel = null, dayLabel = null)
        else {
            val first = album.items.first()
            val time = if (sort.field == "mtime") first.mtime else first.effectiveTime
            val date = TimeLabels.instant(time).atZone(zone).toLocalDate()
            val label = labels.date(time, now, zone)
            album.copy(dateLabel = if (date != previous) label else null, dayLabel = label)
                .also { previous = date }
        }
    }
}
