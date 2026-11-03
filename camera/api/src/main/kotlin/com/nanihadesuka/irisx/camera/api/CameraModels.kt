package com.nanihadesuka.irisx.camera.api

/** A file listed on the camera. */
data class PhotoRef(
    val handle: Int,
    val name: String,
    val sizeBytes: Long,
) {
    /** Only JPEGs are synced; RAW (RAF) and other files are ignored. */
    val isJpeg: Boolean get() = name.substringAfterLast('.', "").lowercase() in JPEG_EXTENSIONS

    /** Stable identity across sessions (handles are reused after a card format). */
    val ledgerKey: String get() = "$name:$sizeBytes"

    private companion object {
        val JPEG_EXTENSIONS = setOf("jpg", "jpeg")
    }
}

/** One photo in the camera's catalog (first page only). */
class CatalogEntry(
    val handle: Int,
    val name: String,
    val sizeBytes: Long,
    /** JPEG thumbnail bytes from the camera, or null when the camera sent none. */
    val thumbnail: ByteArray?,
    /** The SDK's orientation: 1 upright, 2 = turn 90 degrees, 3 = 180, 4 = 270. 0 = not read yet (older cache file). */
    val orientation: Int = 0,
    /** The camera's capture time as sent, such as 20261101T101530. Empty = not read yet (older cache file). */
    val capturedAt: String = "",
    /** The photo's real size from the camera, used to crop the belts from its 4:3 thumbnail. 0 = not read yet, -1 = camera sent none. */
    val pixelWidth: Int = 0,
    val pixelHeight: Int = 0,
) {
    /** Long side over short side, whichever way the photo is turned; null when unknown. */
    val shapeRatio: Float? get() = if (pixelWidth > 0 && pixelHeight > 0) maxOf(pixelWidth, pixelHeight).toFloat() / minOf(pixelWidth, pixelHeight) else null
    /** Camera clock read as local time; null when unknown. */
    val capturedMillis: Long? by lazy {
        val digits = capturedAt.filter(Char::isDigit)
        runCatching {
            java.time.LocalDateTime.parse(digits.take(14).padEnd(14, '0'), java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
    val rotationDegrees: Int get() = when (orientation) {
        2 -> 90
        3 -> 180
        4 -> 270
        else -> 0
    }
    val isJpeg: Boolean get() = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")
    val isVideo: Boolean get() = isVideoName(name)
}

fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mov", "mp4")

/** Why a Wi-Fi/PTP session is opened; the camera is told a different function mode for each. */
enum class SessionPurpose { ReservedTransfer, PhotoCatalog }
