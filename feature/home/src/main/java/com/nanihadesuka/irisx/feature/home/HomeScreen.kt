package com.nanihadesuka.irisx.feature.home

import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.nanihadesuka.irisx.core.designsystem.IrisXTheme
import com.nanihadesuka.irisx.core.designsystem.OrbMood
import com.nanihadesuka.irisx.core.designsystem.StatusColors
import com.nanihadesuka.irisx.core.designsystem.color
import com.nanihadesuka.irisx.domain.model.Attention
import com.nanihadesuka.irisx.domain.model.Camera
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.TransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The camera card on top, the latest photos below. A tap on a photo opens it immersive; its tools live there. */
@Composable
fun HomeScreen(
    ui: HomeUiState,
    status: LinkStatus,
    now: Long,
    onOpenPhoto: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onPair: () -> Unit,
    onRepair: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
    onOpenCamera: (Int) -> Unit = {},
) {
    val frames = ui.photos.take(MAX_FRAMES)
    // Newest first: what is arriving, then what the camera has and the phone does not, then the phone's photos.
    val pages = buildList {
        ui.incoming?.let { add(HomePage.Arriving(it)) }
        ui.latestOnCamera?.let { add(HomePage.OnCamera(it)) }
        frames.forEach { add(HomePage.OnPhone(it)) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CameraCard(ui, status, now, onOpenSettings, onPair, onRepair, onRetry, onResume, onStop, onTogglePause)
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            if (pages.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.CameraAlt, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Your latest photo appears here", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                val pager = rememberPagerState { pages.size }
                HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages[it].key }) { index ->
                    when (val page = pages[index]) {
                        is HomePage.Arriving -> Shimmer(Modifier.fillMaxSize())
                        is HomePage.OnCamera -> CameraThumb(
                            page.entry,
                            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onOpenCamera(page.entry.handle) },
                            contentScale = ContentScale.Crop,
                            sharedKey = "h${page.entry.handle}",
                        )
                        is HomePage.OnPhone -> FullFrame(
                            page.photo,
                            Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { onOpenPhoto(page.photo.uri) },
                        )
                    }
                }
                pages.getOrNull(pager.currentPage)?.let { page ->
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                            .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 10.dp),
                    ) {
                        val (title, detail) = when (page) {
                            is HomePage.Arriving -> "New photo arriving" to if (page.left > 1) "${page.left} on their way" else "On its way from the camera"
                            is HomePage.OnCamera -> page.entry.name to "On the camera. Tap to see it"
                            is HomePage.OnPhone -> page.photo.name to "${relativeTime(now - page.photo.moment)}  ·  ${fileSize(page.photo.sizeBytes)}"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                        Text("$detail  ·  ${pager.currentPage + 1} of ${pages.size}", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f), maxLines = 1)
                    }
                }
            }
        }
    }
}

private sealed interface HomePage {
    val key: String
    data class Arriving(val left: Int) : HomePage { override val key get() = "arriving" }
    data class OnCamera(val entry: CatalogEntry) : HomePage { override val key get() = "camera:${entry.name}" }
    data class OnPhone(val photo: SavedPhoto) : HomePage { override val key get() = photo.uri }
}

/** Connection, how much of the card is on the phone, and on tap the details and link controls. */
@Composable
private fun CameraCard(
    ui: HomeUiState,
    status: LinkStatus,
    now: Long,
    onOpenSettings: () -> Unit,
    onPair: () -> Unit,
    onRepair: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onTogglePause: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val step = nextStep(ui, status, onPair, onRepair, onRetry, onResume, onStop)
    val total = ui.cameraTotal
    val moodColor = status.mood.color()
    Column(
        Modifier.fillMaxWidth().animateContentSize(tween(220)).clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer).clickable { open = !open }.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).background(moodColor.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                StatusIcon(status, Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                RollingText(status.title, MaterialTheme.typography.titleMedium, MaterialTheme.colorScheme.onSurface)
                Text(status.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                status.amount?.let { RollingText(it, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (ui.camera != null) {
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (ui.settings.syncPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        if (ui.settings.syncPaused) "Resume camera connection" else "Pause camera connection",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusProgress(status)
        step?.let { (label, run) -> Button(onClick = run, Modifier.fillMaxWidth()) { Text(label) } }
        if (open) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat(total?.toString() ?: "--", "On card", Modifier.weight(1f))
                Stat(ui.photos.size.toString(), "On phone", Modifier.weight(1f))
                Stat(ui.lastLocationMillis?.let { relativeTime(now - it) } ?: "--", "Location sent", Modifier.weight(1f))
            }
            if (ui.camera != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onRetry, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reconnect")
                    }
                    // Settings holds the confirmation that says what unpairing does.
                    FilledTonalButton(onClick = onOpenSettings, Modifier.weight(1f)) {
                        Icon(Icons.Rounded.LinkOff, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Unpair")
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
        Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The fix for what is blocking the link, or null when the app has it in hand. */
private fun nextStep(
    ui: HomeUiState,
    status: LinkStatus,
    onPair: () -> Unit,
    onRepair: () -> Unit,
    onRetry: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
): Pair<String, () -> Unit>? {
    val c = ui.connection
    return when {
        ui.camera == null -> "Pair" to onPair
        status.radio != null -> status.retryLabel to onRetry
        status.stoppable -> "Stop" to onStop
        ui.settings.syncPaused -> "Resume" to onResume
        c is ConnectionState.NeedsAttention && c.reason == Attention.PairingLost -> "Pair again" to onRepair
        c is ConnectionState.NeedsAttention && c.reason == Attention.PermissionMissing -> "Allow" to onPair
        ui.transfer is TransferState.Failed && status.mood == OrbMood.Attention -> "Retry" to onRetry
        else -> null
    }
}

/** Decoded at screen size, because a system thumbnail can be far smaller and looks soft this large. */
private val fullFrames = object : LruCache<String, ImageBitmap>(96 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}

@Composable
private fun FullFrame(photo: SavedPhoto, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(fullFrames.get(photo.uri), photo.uri) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(photo.uri))) { decoder, info, _ ->
                        val longest = maxOf(info.size.width, info.size.height)
                        if (longest > FRAME_DECODE_PX) decoder.setTargetSampleSize(longest / FRAME_DECODE_PX)
                    }.asImageBitmap().also { fullFrames.put(photo.uri, it) }
                }.getOrNull()
            }
        }
    }
    Box(modifier) {
        val full = bitmap
        if (full != null) {
            Image(full, photo.name, Modifier.fillMaxSize().sharedPhoto(photo.uri, full), contentScale = ContentScale.Crop)
        } else {
            PhoneThumb(photo.uri, photo.name, Modifier.fillMaxSize(), sizePx = 720, sharedKey = photo.uri)
        }
    }
}

/** When it was shot, or when it arrived if the file does not say. */
private val SavedPhoto.moment: Long get() = takenAtMillis.takeIf { it > 0 } ?: savedAtMillis

private const val MAX_FRAMES = 200
private const val FRAME_DECODE_PX = 2400

@Preview(name = "Home - connected", showBackground = true, heightDp = 780)
@Composable
private fun HomePreview() = IrisXTheme {
    val ui = HomeUiState(Camera("00", "X100VI"), ConnectionState.Connected, lastLocationMillis = System.currentTimeMillis() - 60_000, loaded = true, cameraMissing = 14, cameraTotal = 327)
    HomeScreen(ui, LinkStatus(OrbMood.Connected, Icons.Rounded.CameraAlt, "Connected", "Ready for new photos."), System.currentTimeMillis(), {}, {}, {}, {}, {}, {}, {}, {})
}
