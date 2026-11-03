package com.nanihadesuka.irisx.feature.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.nanihadesuka.irisx.domain.model.CompressionMode
import com.nanihadesuka.irisx.domain.model.CompressionPreview
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private class Comparison(
    val before: Bitmap,
    /** Null when the settings would leave this photo as it is. */
    val after: Bitmap?,
    val difference: Bitmap?,
    val beforeBytes: Int,
    val afterBytes: Int,
    val beforeSize: String,
    val afterSize: String,
)

private enum class CompareMode { Slider, Difference }

/** The result of compressing, before anything is saved: drag to compare, pinch to look closer. */
@Composable
fun CompressPreviewDialog(
    photos: List<SavedPhoto>,
    settings: Settings,
    load: suspend (SavedPhoto, Settings) -> CompressionPreview,
    onCancel: () -> Unit,
    onConfirm: (Map<String, Settings>) -> Unit,
) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var index by remember { mutableIntStateOf(0) }
        var mode by remember { mutableStateOf(CompareMode.Slider) }
        val photo = photos[index.coerceIn(photos.indices)]
        // Settings changed here apply to this photo only and are not saved.
        var custom by remember { mutableStateOf(mapOf<String, Settings>()) }
        var adjusting by remember { mutableStateOf(false) }
        val current = custom[photo.uri] ?: settings
        val comparison by produceState<Comparison?>(null, photo.uri, current) {
            value = null
            delay(RECOMPRESS_DELAY_MS) // while a slider moves, only the last value is worked out
            value = runCatching { withContext(Dispatchers.Default) { compare(load(photo, current)) } }.getOrNull()
        }
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(photo.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                val c = comparison
                Text(
                    when {
                        c == null -> "Compressing a preview. Nothing is saved yet"
                        c.after == null -> "Already at or below this size, it would stay as it is"
                        else -> "${fileSize(c.beforeBytes.toLong())} to ${fileSize(c.afterBytes.toLong())}, " +
                            "${100 - c.afterBytes * 100 / c.beforeBytes.coerceAtLeast(1)}% smaller. ${c.beforeSize} to ${c.afterSize}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == CompareMode.Slider, { mode = CompareMode.Slider }, { Text("Compare") })
                    FilterChip(mode == CompareMode.Difference, { mode = CompareMode.Difference }, { Text("Difference") })
                    FilterChip(adjusting, { adjusting = !adjusting }, { Text(if (photo.uri in custom) "Adjust (custom)" else "Adjust") })
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val c = comparison
                when {
                    c == null -> CircularProgressIndicator()
                    c.after == null -> Image(c.before.asImageBitmap(), photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    else -> Crossfade(mode, animationSpec = tween(180), label = "compare") {
                        if (it == CompareMode.Slider) SliderCompare(c.before, c.after) else DifferenceView(c.difference)
                    }
                }
            }
            AnimatedVisibility(adjusting) {
                PhotoSettings(
                    current,
                    isCustom = photo.uri in custom,
                    onChange = { custom = custom + (photo.uri to it) },
                    onReset = { custom = custom - photo.uri },
                )
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (photos.size > 1) {
                        TextButton(onClick = { index = (index + photos.size - 1) % photos.size }) { Text("Previous") }
                        Text("${index + 1} / ${photos.size}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        TextButton(onClick = { index = (index + 1) % photos.size }) { Text("Next") }
                    }
                }
                Button(onClick = { onConfirm(custom) }) { Text(if (photos.size == 1) "Compress" else "Compress ${photos.size}") }
            }
        }
    }
}

@Composable
private fun PhotoSettings(s: Settings, isCustom: Boolean, onChange: (Settings) -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(CompressionMode.TargetSize to "Size", CompressionMode.Percent to "Percent", CompressionMode.Manual to "Manual").forEach { (mode, label) ->
                FilterChip(s.compressionMode == mode, { onChange(s.copy(compressionMode = mode)) }, { Text(label) })
            }
            if (isCustom) TextButton(onClick = onReset) { Text("Reset") }
        }
        when (s.compressionMode) {
            CompressionMode.TargetSize -> Adjust("${"%.2f".format(s.compressionTargetKb / 1024f)} MB", s.compressionTargetKb, 256..5120, 128) { onChange(s.copy(compressionTargetKb = it)) }
            CompressionMode.Percent -> Adjust("${s.compressionPercent}% smaller", s.compressionPercent, 10..90, 5) { onChange(s.copy(compressionPercent = it)) }
            CompressionMode.Manual -> {
                Adjust("Longest side ${s.compressionMaxEdge} px", s.compressionMaxEdge, 1024..7728, 256) { onChange(s.copy(compressionMaxEdge = it)) }
                Adjust("Quality ${s.compressionQuality}", s.compressionQuality, 40..95, 5) { onChange(s.copy(compressionQuality = it)) }
            }
        }
    }
}

@Composable
private fun Adjust(label: String, value: Int, range: IntRange, step: Int, onValue: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    Slider(
        value = value.toFloat(),
        onValueChange = { onValue(((it - range.first) / step).roundToInt() * step + range.first) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
    )
}

/** One finger moves the divider; two fingers zoom and pan both images together. */
@Composable
private fun SliderCompare(before: Bitmap, after: Bitmap) {
    var split by remember { mutableFloatStateOf(0.5f) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size == 1) {
                        split = (pressed[0].position.x / size.width).coerceIn(0f, 1f)
                    } else {
                        scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                        pan = if (scale == 1f) Offset.Zero else pan + event.calculatePan()
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        val transform = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = pan.x
            translationY = pan.y
        }
        Image(after.asImageBitmap(), "Compressed", transform, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        Box(
            Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
            },
        ) {
            Image(before.asImageBitmap(), "Original", transform, contentScale = ContentScale.Fit, filterQuality = FilterQuality.High)
        }
        Box(Modifier.offset(x = maxWidth * split - 1.dp).width(2.dp).fillMaxHeight().background(Color.White))
        Label("Original", Modifier.align(Alignment.TopStart))
        Label("Compressed", Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun DifferenceView(difference: Bitmap?) {
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
            awaitEachGesture {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.none { it.pressed }) break
                    scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                    pan = if (scale == 1f) Offset.Zero else pan + event.calculatePan()
                    event.changes.forEach { it.consume() }
                }
            }
        },
    ) {
        if (difference != null) {
            Image(
                difference.asImageBitmap(),
                "Difference",
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
                contentScale = ContentScale.Fit,
            )
        }
        Label("Black = unchanged, bright = changed (shown ${DIFF_GAIN}x stronger)", Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(
        text,
        modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
    )
}

private fun compare(preview: CompressionPreview): Comparison {
    val before = uprightBitmap(preview.original, 0, COMPARE_EDGE) ?: error("Not a readable JPEG")
    val compressed = preview.compressed
    // Same pixel size and the same drawing path for both, or the side compared would depend on how each was scaled.
    val after = compressed?.let { uprightBitmap(it, 0, COMPARE_EDGE) }?.let { Bitmap.createScaledBitmap(it, before.width, before.height, true) }
    return Comparison(
        before = before,
        after = after,
        difference = after?.let { differenceOf(before, it) },
        beforeBytes = preview.original.size,
        afterBytes = compressed?.size ?: preview.original.size,
        beforeSize = dimensions(preview.original),
        afterSize = compressed?.let(::dimensions) ?: "",
    )
}

private fun dimensions(jpeg: ByteArray): String {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    return "${bounds.outWidth} x ${bounds.outHeight}"
}

/** Per-channel difference, amplified, because good compression is otherwise nearly black. */
private fun differenceOf(before: Bitmap, after: Bitmap): Bitmap {
    val longest = maxOf(before.width, before.height)
    val scale = (DIFF_EDGE.toFloat() / longest).coerceAtMost(1f)
    val width = (before.width * scale).toInt().coerceAtLeast(1)
    val height = (before.height * scale).toInt().coerceAtLeast(1)
    val first = IntArray(width * height)
    val second = IntArray(width * height)
    Bitmap.createScaledBitmap(before, width, height, true).getPixels(first, 0, width, 0, 0, width, height)
    Bitmap.createScaledBitmap(after, width, height, true).getPixels(second, 0, width, 0, 0, width, height)
    fun channel(a: Int, b: Int, shift: Int) = (abs((a shr shift and 0xFF) - (b shr shift and 0xFF)) * DIFF_GAIN).coerceAtMost(255)
    val out = IntArray(first.size) { i ->
        val a = first[i]
        val b = second[i]
        (0xFF shl 24) or (channel(a, b, 16) shl 16) or (channel(a, b, 8) shl 8) or channel(a, b, 0)
    }
    return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
}

private const val MAX_SCALE = 8f
private const val RECOMPRESS_DELAY_MS = 350L
private const val COMPARE_EDGE = 4096
private const val DIFF_EDGE = 1440
private const val DIFF_GAIN = 8
