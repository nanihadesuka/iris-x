package com.nanihadesuka.irisx.data.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.nanihadesuka.irisx.domain.model.CompressionMode
import com.nanihadesuka.irisx.domain.model.Settings
import java.io.ByteArrayOutputStream

/** Android's own JPEG codec. Pixels stay as stored, so the original EXIF, with its orientation and location, still applies. */
internal object JpegCompressor {
    /** The smaller JPEG with the original's EXIF, or null when there is nothing to gain. */
    fun compress(original: ByteArray, settings: Settings): ByteArray? {
        val exif = exifSegment(original)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val encoded = when (settings.compressionMode) {
            CompressionMode.Manual -> atSize(original, longest, settings.compressionMaxEdge.coerceAtMost(longest)) { encode(it, settings.compressionQuality) }
            CompressionMode.TargetSize -> toBudget(original, longest, (settings.compressionTargetKb * 1024L - (exif?.size ?: 0)).toInt())
            CompressionMode.Percent -> toBudget(original, longest, (original.size * (100 - settings.compressionPercent) / 100) - (exif?.size ?: 0))
        } ?: return null
        val result = withExif(encoded, exif)
        return result.takeIf { it.size < original.size }
    }

    /** The largest size and best quality that fit: sharpness first, so resolution only drops when quality would suffer. */
    private fun toBudget(original: ByteArray, longest: Int, budget: Int): ByteArray? {
        if (original.size <= budget) return null
        val edges = (listOf(longest) + EDGES.filter { it < longest })
        var smallest: ByteArray? = null
        for (edge in edges) {
            val fit = atSize(original, longest, edge) { bitmap ->
                val floor = encode(bitmap, GOOD_QUALITY)
                if (floor.size > budget) {
                    if (edge == edges.last()) smallest = encode(bitmap, LAST_RESORT_QUALITY)
                    return@atSize null
                }
                var best = floor
                var low = GOOD_QUALITY
                var high = BEST_QUALITY
                while (high - low > 2) {
                    val quality = (low + high) / 2
                    val attempt = encode(bitmap, quality)
                    if (attempt.size <= budget) {
                        best = attempt
                        low = quality
                    } else {
                        high = quality
                    }
                }
                best
            }
            if (fit != null) return fit
        }
        return smallest
    }

    private inline fun <T> atSize(original: ByteArray, longest: Int, edge: Int, use: (Bitmap) -> T): T {
        var sample = 1
        while (longest / (sample * 2) >= edge) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Not a readable JPEG")
        val scale = edge.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        try {
            return use(bitmap)
        } finally {
            if (bitmap !== decoded) bitmap.recycle()
            decoded.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** The whole APP1 Exif segment, marker included. */
    private fun exifSegment(jpeg: ByteArray): ByteArray? {
        var at = 2
        while (at + 4 <= jpeg.size && jpeg[at] == MARKER) {
            val type = jpeg[at + 1].toInt() and 0xFF
            if (type == START_OF_SCAN) return null
            val length = ((jpeg[at + 2].toInt() and 0xFF) shl 8) or (jpeg[at + 3].toInt() and 0xFF)
            if (type == APP1 && at + 10 <= jpeg.size && String(jpeg, at + 4, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                return jpeg.copyOfRange(at, (at + 2 + length).coerceAtMost(jpeg.size))
            }
            at += 2 + length
        }
        return null
    }

    private fun withExif(encoded: ByteArray, exif: ByteArray?): ByteArray {
        if (exif == null) return encoded
        return encoded.copyOfRange(0, 2) + exif + encoded.copyOfRange(2, encoded.size)
    }

    private const val MARKER = 0xFF.toByte()
    private const val APP1 = 0xE1
    private const val START_OF_SCAN = 0xDA
    private const val GOOD_QUALITY = 70
    private const val BEST_QUALITY = 92
    private const val LAST_RESORT_QUALITY = 50
    private val EDGES = listOf(5472, 3840, 3072, 2560, 2048, 1600, 1280, 1024)
}
