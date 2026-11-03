package com.nanihadesuka.irisx.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a photo sits on the screen beneath the viewer, and the image already showing there. */
class PhotoSource {
    var area: Rect = Rect.Zero
    var bitmap: ImageBitmap? = null
    /** The part of [bitmap] that is drawn; null is all of it. */
    var crop: IntRect? = null
    /** The whole image is shown inside [area], rather than cropped to fill it. */
    var fitted: Boolean = false

    /** Where the image itself is drawn. */
    val bounds: Rect
        get() {
            val image = bitmap
            if (!fitted || image == null || area.isEmpty) return area
            val shownWidth = crop?.width ?: image.width
            val shownHeight = crop?.height ?: image.height
            val scale = min(area.width / shownWidth, area.height / shownHeight)
            val size = Size(shownWidth * scale, shownHeight * scale)
            return Rect(area.center - Offset(size.width / 2, size.height / 2), size)
        }
}

val LocalPhotoSources = staticCompositionLocalOf<MutableMap<String, PhotoSource>> { mutableMapOf() }

/** Lets the viewer open from and close back to this spot. */
@Composable
fun Modifier.sharedPhoto(key: String?, bitmap: ImageBitmap?, fitted: Boolean = false, crop: IntRect? = null): Modifier {
    if (key == null) return this
    val sources = LocalPhotoSources.current
    val source = remember(key) { PhotoSource() }
    SideEffect {
        source.bitmap = bitmap
        source.crop = crop
        source.fitted = fitted
        sources[key] = source
    }
    DisposableEffect(key) {
        onDispose { if (sources[key] === source) sources.remove(key) }
    }
    return onGloballyPositioned { source.area = it.boundsInWindow() }
}

/** One photo on its way between its spot on a screen and full screen. [scale] and [dy] are where a drag left it. */
class Flight(val bitmap: ImageBitmap, val from: Rect, val scale: Float = 1f, val dy: Float = 0f, val crop: IntRect? = null)

/** Draws the image cropped to [Flight.from] at progress 0 and fitted to the screen at 1, without a jump at either end. */
@Composable
fun PhotoFlight(flight: Flight, progress: () -> Float, alpha: () -> Float, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val bitmap = flight.bitmap
        val crop = flight.crop ?: IntRect(0, 0, bitmap.width, bitmap.height)
        val width = crop.width.toFloat()
        val height = crop.height.toFloat()
        val fitScale = min(size.width / width, size.height / height) * flight.scale
        val fitSize = Size(width * fitScale, height * fitScale)
        val fit = Rect(Offset((size.width - fitSize.width) / 2, (size.height - fitSize.height) / 2 + flight.dy), fitSize)
        val from = flight.from
        val coverScale = max(from.width / width, from.height / height)
        val coverSize = Size(width * coverScale, height * coverScale)
        val cover = Rect(from.center - Offset(coverSize.width / 2, coverSize.height / 2), coverSize)

        val p = progress()
        val image = lerp(cover, fit, p)
        val radius = CORNER.toPx() * (1f - p)
        val clip = Path().apply { addRoundRect(RoundRect(lerp(from, fit, p), CornerRadius(radius))) }
        clipPath(clip) {
            drawImage(
                bitmap,
                srcOffset = crop.topLeft,
                srcSize = crop.size,
                dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()),
                dstSize = IntSize(image.width.roundToInt(), image.height.roundToInt()),
                alpha = alpha(),
            )
        }
    }
}

private val CORNER = 12.dp
