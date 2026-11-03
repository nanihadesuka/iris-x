package com.nanihadesuka.irisx.feature.home

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntRect
import java.io.ByteArrayInputStream

/** Decoded thumbnails, bounded by bytes so a long scroll cannot run the app out of memory. */
internal val thumbnails = object : LruCache<String, ShownPhoto>(64 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ShownPhoto) = value.bitmap.width * value.bitmap.height * 4
}

/**
 * An image and the part of it to draw. The black belts are never cut out of the image: drawing simply leaves them out,
 * so the source stays as it came, and the grid, the viewer and the flight between them all crop the same way.
 */
class ShownPhoto(val bitmap: ImageBitmap, val crop: IntRect) {
    val painter = BitmapPainter(bitmap, crop.topLeft, crop.size)
}

/** Animated placeholder for an image that is still on its way. */
@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val light = MaterialTheme.colorScheme.surfaceContainerHigh
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0f, 1_000f, infiniteRepeatable(tween(1_200)), label = "shimmerX",
    )
    Box(modifier.background(Brush.linearGradient(listOf(base, light, base), Offset(x - 400f, 0f), Offset(x, 400f))))
}

@Composable
private fun FadedImage(photo: ShownPhoto?, description: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop, sharedKey: String? = null, placeholder: Boolean = true) {
    // Fades in only when it arrives while on screen; one already in memory just appears when scrolled to.
    val alpha = remember { Animatable(if (photo == null) 0f else 1f) }
    LaunchedEffect(photo != null) { if (photo != null) alpha.animateTo(1f, tween(220)) }
    Box(modifier.sharedPhoto(sharedKey, photo?.bitmap, fitted = contentScale == ContentScale.Fit, crop = photo?.crop)) {
        if (photo == null) {
            if (placeholder) Shimmer(Modifier.fillMaxSize())
        } else {
            Image(photo.painter, description, Modifier.fillMaxSize().alpha(alpha.value), contentScale = contentScale)
        }
    }
}

/** A photo saved on the phone. */
@Composable
fun PhoneThumb(uri: String, description: String, modifier: Modifier = Modifier, sizePx: Int = 360, contentScale: ContentScale = ContentScale.Crop, sharedKey: String? = null, placeholder: Boolean = true) {
    val context = LocalContext.current
    val key = "$uri@$sizePx"
    val bitmap by produceState(thumbnails.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(sizePx, sizePx), null).let { ShownPhoto(it.asImageBitmap(), visibleArea(it, null)) } }
                    .getOrNull()?.also { thumbnails.put(key, it) }
            }
        }
    }
    FadedImage(bitmap, description, modifier, contentScale, sharedKey, placeholder = placeholder)
}

/**
 * Camera JPEGs of portrait shots are stored sideways. Thumbnails carry no EXIF, so the turn comes from the SDK's
 * [fallbackDegrees], unless the image has its own orientation tag.
 */
fun decodeUpright(bytes: ByteArray, fallbackDegrees: Int, shapeRatio: Float? = null): ShownPhoto? =
    uprightBitmap(bytes, fallbackDegrees)?.let { ShownPhoto(it.asImageBitmap(), visibleArea(it, shapeRatio)) }

/** The part of [original] that is the photo: from its real shape when known, otherwise from what looks like belts. */
internal fun visibleArea(original: Bitmap, shapeRatio: Float?): IntRect {
    val bitmap = if (original.config == Bitmap.Config.HARDWARE) original.copy(Bitmap.Config.ARGB_8888, false) else original
    return shapeRatio?.let { areaOfShape(bitmap, it) } ?: areaWithoutBelts(bitmap)
}

/**
 * Fits the photo's real shape in the frame, so a dark photo is treated like any other. The band left out must really
 * be black: if it is not, the frame is not what the size suggests, and null leaves it to [areaWithoutBelts].
 */
private fun areaOfShape(bitmap: Bitmap, ratio: Float): IntRect? {
    val long = maxOf(bitmap.width, bitmap.height)
    val short = minOf(bitmap.width, bitmap.height)
    val frame = long.toFloat() / short
    if (kotlin.math.abs(frame - ratio) < 0.02f) return IntRect(0, 0, bitmap.width, bitmap.height)
    val landscape = bitmap.width >= bitmap.height
    // Wider than the frame: the short side has belts. Narrower: the long side does.
    val keepLong = if (ratio > frame) long else (short * ratio).toInt()
    val keepShort = if (ratio > frame) (long / ratio).toInt() else short
    val width = if (landscape) keepLong else keepShort
    val height = if (landscape) keepShort else keepLong
    val x = (bitmap.width - width) / 2
    val y = (bitmap.height - height) / 2
    if (width <= 0 || height <= 0 || x < 0 || y < 0) return null
    fun black(px: Int, py: Int) = bitmap.getPixel(px, py).let { (it shr 16 and 0xFF) < BELT_DARK && (it shr 8 and 0xFF) < BELT_DARK && (it and 0xFF) < BELT_DARK }
    // Middle of the band, away from the JPEG blur at its inner edge.
    val inBand = if (y > 0) (0 until bitmap.width step 8).all { black(it, y / 2) && black(it, bitmap.height - 1 - y / 2) }
    else (0 until bitmap.height step 8).all { black(x / 2, it) && black(bitmap.width - 1 - x / 2, it) }
    return if (inBand) IntRect(x, y, x + width, y + height) else null
}

/**
 * The camera letterboxes thumbnails into 4:3, so a 16:9 photo has black belts (60 px of 480, observed). They are left
 * out only when equal on both sides and not too deep, so a dark photo keeps its dark edges.
 */
private fun areaWithoutBelts(bitmap: Bitmap): IntRect {
    val width = bitmap.width
    val height = bitmap.height
    val whole = IntRect(0, 0, width, height)
    fun dark(pixels: IntArray) = pixels.count { (it shr 16 and 0xFF) < BELT_DARK && (it shr 8 and 0xFF) < BELT_DARK && (it and 0xFF) < BELT_DARK } >= pixels.size * 98 / 100
    fun isRow(y: Int) = dark(IntArray(width).also { bitmap.getPixels(it, 0, width, 0, y, width, 1) })
    fun isColumn(x: Int) = dark(IntArray(height).also { bitmap.getPixels(it, 0, 1, x, 0, 1, height) })
    /** Depth of equal belts on both sides, or 0. */
    fun depth(size: Int, isBelt: (Int) -> Boolean): Int {
        val limit = size / 4
        var start = 0
        while (start <= limit && isBelt(start)) start++
        var end = 0
        while (end <= limit && isBelt(size - 1 - end)) end++
        return if (start == 0 || start > limit || end > limit || kotlin.math.abs(start - end) > 2) 0 else maxOf(start, end)
    }
    val top = depth(height, ::isRow)
    // A turned portrait has them on the sides.
    val side = if (top == 0) depth(width, ::isColumn) else 0
    val shownWidth = width - 2 * side
    val shownHeight = height - 2 * top
    if ((top == 0 && side == 0) || shownWidth <= 0 || shownHeight <= 0) return whole
    // Only a shape cameras shoot: a dark photo with dark edges otherwise keeps them.
    val ratio = maxOf(shownWidth, shownHeight).toFloat() / minOf(shownWidth, shownHeight)
    if (BELT_RATIOS.none { kotlin.math.abs(ratio - it) < 0.05f }) return whole
    return IntRect(side, top, side + shownWidth, top + shownHeight)
}

private val BELT_RATIOS = listOf(1f, 3f / 2f, 16f / 9f)
private const val BELT_DARK = 24

/** [maxEdge] keeps a large photo small enough to draw. */
fun uprightBitmap(bytes: ByteArray, fallbackDegrees: Int, maxEdge: Int = Int.MAX_VALUE): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val tagged = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    val degrees = if (tagged != 0) tagged else fallbackDegrees
    if (degrees == 0) return bitmap
    val turn = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, true)
}

/** A date section of a grid. The circle selects or clears everything in it that can be selected. */
@Composable
fun SectionHeader(title: String, count: Int, allSelected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.Text(title, Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = androidx.compose.material3.MaterialTheme.colorScheme.primary, maxLines = 1)
        androidx.compose.material3.Text("$count", style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.IconButton(onClick = onToggle) {
            androidx.compose.material3.Icon(
                if (allSelected) androidx.compose.material.icons.Icons.Rounded.CheckCircle else androidx.compose.material.icons.Icons.Rounded.RadioButtonUnchecked,
                if (allSelected) "Clear $title" else "Select $title",
                tint = if (allSelected) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thumbnail read from the camera. */
@Composable
fun CameraThumb(entry: CatalogEntry, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit, sharedKey: String? = null) {
    val key = "camera:${entry.name}:${entry.sizeBytes}:${entry.orientation}:${entry.thumbnail?.size}:${entry.pixelWidth}"
    val bitmap by produceState(thumbnails.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                entry.thumbnail?.let { decodeUpright(it, entry.rotationDegrees, entry.shapeRatio) }?.also { thumbnails.put(key, it) }
            }
        }
    }
    FadedImage(bitmap, entry.name, modifier, contentScale, sharedKey)
}

fun sharePhotos(context: Context, uris: List<String>) {
    if (uris.isEmpty()) return
    val parsed = uris.map(Uri::parse)
    val send = if (parsed.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, parsed.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(parsed))
    }
    val types = parsed.map { context.contentResolver.getType(it).orEmpty() }
    send.setType(if (types.all { it.startsWith("image") }) "image/jpeg" else if (types.all { it.startsWith("video") }) "video/*" else "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The clip data is what carries the read grant through the chooser to the target app.
    send.clipData = ClipData.newRawUri(null, parsed.first()).apply { parsed.drop(1).forEach { addItem(ClipData.Item(it)) } }
    context.startActivity(Intent.createChooser(send, null))
}

fun openGooglePhotos(context: Context, onMissing: () -> Unit) {
    val open = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
    if (open != null) context.startActivity(open) else onMissing()
}

/** Deletes PHONE copies through the system's own confirmation. Nothing here can reach the camera. */
@Composable
fun rememberPhoneDelete(onDeleted: () -> Unit = {}): (List<String>) -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) onDeleted()
    }
    return { uris ->
        val request = MediaStore.createDeleteRequest(context.contentResolver, uris.map(Uri::parse))
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
}

fun relativeTime(deltaMs: Long): String {
    val s = (deltaMs / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

fun fileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    else -> "${bytes / 1000} KB"
}
