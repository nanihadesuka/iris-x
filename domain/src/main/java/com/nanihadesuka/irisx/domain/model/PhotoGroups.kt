package com.nanihadesuka.irisx.domain.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

enum class PhotoGrouping { Days, Weeks, Months, Years }

data class PhotoGroup<T>(val key: String, val title: String, val items: List<T>)

/** A day with more photos than this is split into morning, midday, evening and night. */
const val MAX_DAY_SECTION = 48

/**
 * Sections for a photo grid, newest first. Items with no known time ([timeOf] null) go in a last section.
 * Only days are split, because a user who picks weeks or longer asked for larger sections.
 */
fun <T> groupPhotos(
    items: List<T>,
    timeOf: (T) -> Long?,
    grouping: PhotoGrouping,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<PhotoGroup<T>> {
    val (known, unknown) = items.partition { timeOf(it) != null }
    fun dayOf(item: T) = Instant.ofEpochMilli(timeOf(item)!!).atZone(zone).toLocalDate()
    val periods = known.sortedByDescending { timeOf(it)!! }.groupBy { periodStart(dayOf(it), grouping) }
    val groups = periods.flatMap { (start, photos) ->
        val title = periodTitle(start, grouping, today)
        val parts = if (grouping == PhotoGrouping.Days && photos.size > MAX_DAY_SECTION) photos.groupBy { dayPartOf(timeOf(it)!!, zone) } else emptyMap()
        if (parts.size > 1) {
            parts.map { (part, inPart) -> PhotoGroup("$start/${part.name}", "$title, ${part.title}", inPart) }
        } else {
            listOf(PhotoGroup(start.toString(), title, photos))
        }
    }
    return if (unknown.isEmpty()) groups else groups + PhotoGroup("unknown", "Date not read yet", unknown)
}

private enum class DayPart(val title: String, val endHour: Int) {
    Morning("morning", 12),
    Midday("midday", 17),
    Evening("evening", 21),
    Night("night", 24),
}

private fun dayPartOf(millis: Long, zone: ZoneId): DayPart {
    val hour = Instant.ofEpochMilli(millis).atZone(zone).hour
    return DayPart.entries.first { hour < it.endHour }
}

private fun periodStart(day: LocalDate, grouping: PhotoGrouping): LocalDate = when (grouping) {
    PhotoGrouping.Days -> day
    PhotoGrouping.Weeks -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    PhotoGrouping.Months -> day.withDayOfMonth(1)
    PhotoGrouping.Years -> day.withDayOfYear(1)
}

private fun periodTitle(start: LocalDate, grouping: PhotoGrouping, today: LocalDate): String = when (grouping) {
    PhotoGrouping.Days -> when (start) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> start.format(DateTimeFormatter.ofPattern(if (start.year == today.year) "EEE, d MMM" else "EEE, d MMM yyyy"))
    }
    PhotoGrouping.Weeks -> "Week of " + start.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    PhotoGrouping.Months -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
    PhotoGrouping.Years -> start.year.toString()
}
