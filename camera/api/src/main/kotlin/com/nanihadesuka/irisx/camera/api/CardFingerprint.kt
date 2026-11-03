package com.nanihadesuka.irisx.camera.api

/**
 * What a browse learns from the camera's listing calls, kept between visits.
 * [countsByDate] may be empty when the camera did not answer that call: it is then simply not compared.
 */
data class CardFingerprint(val handles: List<Int>, val countsByDate: Map<String, Int>)

enum class CardChange {
    /** Nothing is known about the previous card. Check it before trusting the cache. */
    Unknown,

    /** Same photos: nothing needs reading. */
    Unchanged,

    /** Only newer photos were added, so older handles still mean the same photos. */
    Added,

    /** Something was removed or moved. Handles may have shifted, so check before trusting the cache. */
    Changed,
}

fun CardFingerprint.changeSince(previous: CardFingerprint?): CardChange {
    if (previous == null) return CardChange.Unknown
    val datesKnown = countsByDate.isNotEmpty() && previous.countsByDate.isNotEmpty()
    val sameHandles = handles.toSet() == previous.handles.toSet()
    if (sameHandles && (!datesKnown || countsByDate == previous.countsByDate)) return CardChange.Unchanged
    val newest = previous.handles.maxOrNull() ?: return CardChange.Unknown
    val onlyNewer = handles.toSet().containsAll(previous.handles) && (handles - previous.handles.toSet()).all { it > newest }
    val noDateShrank = !datesKnown || previous.countsByDate.all { (date, count) -> (countsByDate[date] ?: 0) >= count }
    return if (!sameHandles && onlyNewer && noDateShrank) CardChange.Added else CardChange.Changed
}
