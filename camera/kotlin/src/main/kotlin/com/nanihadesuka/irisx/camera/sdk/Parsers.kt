package com.nanihadesuka.irisx.camera.sdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the camera says about one object. */
internal data class ObjectInfo(
    val handle: Int,
    val name: String,
    val sizeBytes: Long,
    val objectFormat: Int,
    val thumbnailBytes: Int,
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** As sent, such as 20261030T192810. */
    val capturedAt: String,
    /** From the object's keywords; 0 when it sent none. */
    val orientation: Int,
)

/** One name/value pair from a [FujiProp.CHANGES] poll. */
internal data class PropChange(val prop: Int, val value: Long)

/** The card listing: the call that makes the camera walk its index. */
internal data class CardListing(val handles: List<Int>, val countsByDate: Map<String, Int>)

internal fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

/** A PTP string: one byte of length counting the final null, then UTF-16LE. */
internal fun ByteBuffer.ptpString(): String {
    if (!hasRemaining()) return ""
    val chars = get().toInt() and 0xFF
    return buildString {
        repeat(chars) {
            if (remaining() < 2) return@buildString
            val c = short.toInt().toChar()
            if (c != '\u0000') append(c)
        }
    }
}

/** The standard PTP ObjectInfo dataset, plus the "Orientation:n" the camera keeps in its keywords. */
internal fun parseObjectInfo(handle: Int, data: ByteArray): ObjectInfo? {
    if (data.size < OBJECT_INFO_NAME_OFFSET + 1) return null
    val b = data.littleEndian()
    val format = b.getShort(4).toInt() and 0xFFFF
    val size = b.getInt(8).toLong() and 0xFFFFFFFFL
    val thumb = b.getInt(14)
    val width = b.getInt(26)
    val height = b.getInt(30)
    b.position(OBJECT_INFO_NAME_OFFSET)
    val name = b.ptpString()
    val captured = b.ptpString()
    b.ptpString() // modification date
    val keywords = b.ptpString()
    return ObjectInfo(handle, name, size, format, thumb, width, height, captured, keywordNumber(keywords, "Orientation:"))
}

private fun keywordNumber(keywords: String, key: String): Int {
    val at = keywords.indexOf(key)
    if (at < 0) return 0
    return keywords.substring(at + key.length).takeWhile(Char::isDigit).toIntOrNull() ?: 0
}

/** A u16 count, then a u16 property code and a u32 value for each. */
internal fun parseChanges(data: ByteArray): List<PropChange> {
    if (data.size < 2) return emptyList()
    val b = data.littleEndian()
    val count = b.short.toInt() and 0xFFFF
    return buildList {
        repeat(count) {
            if (b.remaining() < 6) return@buildList
            add(PropChange(b.short.toInt() and 0xFFFF, b.int.toLong() and 0xFFFFFFFFL))
        }
    }
}

/** A u32 count, then the handles. Taken as they come if the count does not fit, because this one is not yet captured. */
internal fun parseHandles(data: ByteArray): List<Int> {
    val b = data.littleEndian()
    val words = data.size / 4
    if (words == 0) return emptyList()
    val first = b.getInt(0)
    val prefixed = first == words - 1
    if (prefixed) b.position(4)
    return List(if (prefixed) first else words) { b.int }
}

/** A u32 count, then for each date: an unused u32, the date as a PTP string, and its u32 photo count. */
internal fun parseCountsByDate(data: ByteArray): Map<String, Int> {
    if (data.size < 4) return emptyMap()
    val b = data.littleEndian()
    val count = b.int
    val counts = LinkedHashMap<String, Int>()
    repeat(count) {
        if (b.remaining() < 4) return counts
        b.int
        val date = b.ptpString()
        if (b.remaining() < 4) return counts
        val photos = b.int
        if (date.isNotEmpty()) counts[date] = (counts[date] ?: 0) + photos
    }
    return counts
}

private const val OBJECT_INFO_NAME_OFFSET = 52
