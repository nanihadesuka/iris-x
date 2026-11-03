package com.nanihadesuka.irisx.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.TextStyle
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiFind
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nanihadesuka.irisx.core.designsystem.OrbMood
import com.nanihadesuka.irisx.core.designsystem.color
import com.nanihadesuka.irisx.domain.model.Attention
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.DownloadMode
import com.nanihadesuka.irisx.domain.model.TransferState

/** The one mapping from state to words, color and icon (docs/design/UI_UX_DESIGN.md section 3). */
data class LinkStatus(
    val mood: OrbMood,
    val icon: ImageVector,
    val title: String,
    val detail: String,
    val progress: Float? = null,
    /** Worth a strip above the other tabs: something is happening or wrong. */
    val noteworthy: Boolean = true,
    /** The user can end this: it is a browse, not a transfer the camera asked for. */
    val stoppable: Boolean = false,
    /** Something failed and Retry can repeat it. The same on every screen. */
    val retryable: Boolean = false,
    val retryLabel: String = "Retry",
    /** A phone switch is the problem: the button offers to turn this on. */
    val radio: Radio? = null,
    /** How much of the current file has arrived: stays visible when the capsule settles. */
    val amount: String? = null,
) {
    /** The words without their numbers: what must change before the text is worth a fade. */
    val fadeKey: Pair<String, String> get() = title.filterNot(Char::isDigit) to detail.filterNot(Char::isDigit)
}

/**
 * A line whose digits roll one by one as the number changes, and the rest stays still, as iOS does for numeric text. Digits
 * all take the same width so nothing beside them shifts. Fast changes are shown at a readable pace, not each one.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(text)
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(Unit) {
        while (true) {
            shown = latest
            delay(ROLL_REFRESH_MS)
        }
    }
    val tabular = remember(style) { style.copy(fontFeatureSettings = "tnum") }
    Row(modifier.clipToBounds()) {
        // Counted from the end, so "9.9 MB" to "10.0 MB" keeps the unit and decimals where they are.
        shown.forEachIndexed { index, char ->
            key(shown.length - index) {
                AnimatedContent(
                    char,
                    transitionSpec = {
                        if (initialState.isDigit() && targetState.isDigit()) {
                            val up = targetState > initialState
                            (slideInVertically(tween(ROLL_MS)) { if (up) it else -it } + fadeIn(tween(ROLL_MS))) togetherWith
                                (slideOutVertically(tween(ROLL_MS)) { if (up) -it else it } + fadeOut(tween(ROLL_MS)))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                    },
                    label = "digit",
                ) { Text(it.toString(), style = tabular, color = color, maxLines = 1, softWrap = false) }
            }
        }
    }
}

private const val ROLL_MS = 160
private const val ROLL_REFRESH_MS = 250L

/** Names the usual wait once it has passed, with what the user can do. */
private fun waitDetail(usual: String, ui: HomeUiState, now: Long, usualMs: Long, hint: String): String {
    val waited = now - ui.transferSince
    return if (waited > usualMs) "Taking longer than usual (${waited / 1000} s). $hint" else usual
}

private fun megabytes(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)

fun linkStatus(ui: HomeUiState, now: Long): LinkStatus {
    val t = ui.transfer
    val lastSeen = ui.lastConnectedMillis?.let { " Last seen ${relativeTime(now - it)}." } ?: ""
    val stoppable = ui.browserActive
    return when {
        ui.camera == null -> LinkStatus(OrbMood.Away, Icons.Rounded.CameraAlt, "No camera yet", "Pair your X100VI to get started.", noteworthy = false)
        !ui.settings.syncPaused && ui.bluetoothOff -> LinkStatus(
            OrbMood.Attention, Icons.Rounded.BluetoothDisabled, "Bluetooth is off", "Iris X needs it to reach the camera.",
            retryable = true, retryLabel = "Turn on", radio = Radio.Bluetooth,
        )
        // Only when it stands in the way: with the camera away, Wi-Fi being off changes nothing.
        !ui.settings.syncPaused && ui.wifiOff && (ui.connection == ConnectionState.Connected || t is TransferState.Failed) -> LinkStatus(
            OrbMood.Attention, Icons.Rounded.WifiOff, "Wi-Fi is off", "Photos come from the camera over Wi-Fi.",
            retryable = true, retryLabel = "Turn on", radio = Radio.Wifi,
        )
        t is TransferState.Downloading -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.CloudDownload, "Downloading ${t.done + 1} of ${t.total}",
            waitDetail(
                t.currentName,
                ui, now, STALLED_MS, "No data from the camera. Keep it on and close by.",
            ),
            // This photo's own bytes; the title counts the photos. Unknown until the camera reports its first chunk.
            progress = if (t.size > 0) (t.bytes.toFloat() / t.size).coerceAtMost(1f) else null,
            amount = if (t.size > 0) "${megabytes(t.bytes)} of ${megabytes(t.size)} MB" else null,
            stoppable = stoppable,
        )
        t is TransferState.WaitingForLink -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.BluetoothSearching, "Reconnecting to the camera",
            waitDetail("Looking for it over Bluetooth, then browsing.", ui, now, 20_000, "Check the camera is on and close by."),
            stoppable = stoppable,
        )
        t is TransferState.WaitingForCamera -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.WifiFind, "Step 1 of 3: waking camera Wi-Fi",
            waitDetail("Asking the camera over Bluetooth to start its network.", ui, now, 12_000, "Make sure the camera is awake."),
            stoppable = stoppable,
        )
        t is TransferState.JoiningWifi -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.WifiFind, "Step 2 of 3: joining camera Wi-Fi",
            waitDetail(
                if (t.attempt > 1) "Try ${t.attempt} of 4. The first try often fails." else "Your phone returns to its network afterwards.",
                ui, now, 15_000, "Keep the phone near the camera.",
            ),
            stoppable = stoppable,
        )
        t is TransferState.WifiConnected -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.Wifi, "Step 3 of 3: opening session",
            waitDetail(
                if (t.attempt > 1) "The camera is not answering yet. Try ${t.attempt} of 6." else "Wi-Fi is up, almost there.",
                ui, now, 10_000, "The camera may be busy; it is retried for up to 30 s.",
            ),
            stoppable = stoppable,
        )
        t is TransferState.ReadingCard -> LinkStatus(
            OrbMood.Busy, Icons.Rounded.PhotoLibrary, "Reading the card",
            waitDetail("${t.done} of ${t.total}. Each photo is only read once.", ui, now, STALLED_MS, "No answer from the camera."),
            progress = t.done.toFloat() / t.total.coerceAtLeast(1),
            stoppable = stoppable,
        )
        t is TransferState.Stopping -> LinkStatus(OrbMood.Busy, Icons.Rounded.PhotoLibrary, "Stopping", "Letting the camera finish its current step.")
        t is TransferState.Browsing -> LinkStatus(OrbMood.Connected, Icons.Rounded.PhotoLibrary, "Browsing camera", "On the camera's Wi-Fi. It closes by itself when idle.", stoppable = stoppable)
        t is TransferState.Failed -> LinkStatus(OrbMood.Attention, Icons.Rounded.ErrorOutline, "Could not reach the camera", t.message.take(90), retryable = true)
        ui.settings.syncPaused -> LinkStatus(OrbMood.Away, Icons.Rounded.PauseCircle, "Paused", "The camera is left alone: no Bluetooth, Wi-Fi or location. Resume to reconnect.", noteworthy = false)
        else -> when (val c = ui.connection) {
            ConnectionState.Connected ->
                LinkStatus(OrbMood.Connected, Icons.Rounded.BluetoothConnected, "Connected", when {
                    ui.settings.photosWaiting && ui.settings.downloadMode == DownloadMode.OnlyWhenAsked -> "New photos are on the camera. Refresh to see them."
                    ui.settings.photosWaiting -> "New photos are on the camera. Getting them shortly."
                    ui.settings.downloadMode == DownloadMode.Automatic -> "New photos come over by themselves."
                    ui.settings.downloadMode == DownloadMode.WhenOpened -> "New photos come over when you open Iris X."
                    else -> "New photos come over when you ask."
                }, noteworthy = false)
            ConnectionState.Connecting -> LinkStatus(OrbMood.Busy, Icons.Rounded.BluetoothSearching, "Connecting", "Setting up the Bluetooth link, a few seconds.")
            ConnectionState.CameraAway, ConnectionState.NotPaired -> LinkStatus(OrbMood.Away, Icons.Rounded.BluetoothSearching, "Looking for camera", if (ui.settings.photosWaiting) "New photos are waiting on it. Turn it on to get them." else "Turn it on, it connects by itself.$lastSeen", noteworthy = false)
            is ConnectionState.NeedsAttention -> when (c.reason) {
                Attention.BluetoothOff -> LinkStatus(OrbMood.Attention, Icons.Rounded.BluetoothDisabled, "Bluetooth is off", "Turn it on and Iris X reconnects.")
                Attention.PermissionMissing -> LinkStatus(OrbMood.Attention, Icons.Rounded.Lock, "Permission needed", "Iris X needs access to reach the camera.")
                Attention.PairingLost -> LinkStatus(OrbMood.Attention, Icons.Rounded.ErrorOutline, "Pairing was lost", "Pair the camera again.")
                Attention.LocationOff -> LinkStatus(OrbMood.Attention, Icons.Rounded.LocationOff, "Location is off", "Turn it on to geotag photos.")
            }
        }
    }
}

private const val STALLED_MS = 20_000L

/** A dot that breathes only while real work is happening. */
@Composable
fun StatusDot(mood: OrbMood, modifier: Modifier = Modifier) {
    val color by animateColorAsState(mood.color(), tween(300), label = "dot")
    val scale = if (mood == OrbMood.Busy) {
        val value by rememberInfiniteTransition(label = "breath").animateFloat(
            0.6f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "breathScale",
        )
        value
    } else 1f
    Canvas(modifier.size(10.dp)) {
        drawCircle(color.copy(alpha = 0.25f))
        drawCircle(color, radius = size.minDimension / 3.2f * scale)
    }
}

@Composable
fun StatusProgress(status: LinkStatus, modifier: Modifier = Modifier) {
    val fraction = status.progress
    val color = status.mood.color()
    if (fraction != null) {
        val animated by animateFloatAsState(fraction, tween(300), label = "progress")
        LinearProgressIndicator(progress = { animated }, modifier.fillMaxWidth().height(3.dp), color = color, trackColor = color.copy(alpha = 0.18f), drawStopIndicator = {})
    } else if (status.mood == OrbMood.Busy) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(3.dp), color = color, trackColor = color.copy(alpha = 0.18f))
    }
}

/** What the viewer shows over a photo: only what affects it, so a finished photo stays clean. */
fun viewerStatus(ui: HomeUiState, status: LinkStatus, photoNeedsCamera: Boolean): LinkStatus? = when {
    ui.transfer is TransferState.Browsing -> null
    status.noteworthy -> status
    !photoNeedsCamera || ui.camera == null -> null
    ui.settings.syncPaused -> status.copy(retryable = true, retryLabel = "Connect")
    ui.connection != ConnectionState.Connected -> LinkStatus(
        OrbMood.Away, Icons.Rounded.BluetoothSearching, "Camera not connected",
        "Showing what is saved here. Turn the camera on to download.", retryable = true, retryLabel = "Connect",
    )
    else -> null
}

/**
 * The status over a photo: a small translucent capsule, so the photo stays the subject. Each new state opens to say what
 * is happening and what to do, then settles back to its title. A tap opens it again.
 */
@Composable
fun StatusCapsule(status: LinkStatus, onStop: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(true) }
    LaunchedEffect(status.fadeKey.first) {
        open = true
        delay(CAPSULE_OPEN_MS)
        open = false
    }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.widthIn(max = 340.dp).clip(shape).background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(interactionSource = null, indication = null) { open = !open }
            .animateContentSize(tween(200)),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(status.mood)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                RollingText(status.title, MaterialTheme.typography.labelMedium, Color.White)
                if (open) Text(status.detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                status.amount?.let { RollingText(it, MaterialTheme.typography.bodySmall, Color.White.copy(alpha = 0.75f)) }
            }
            if (status.stoppable || status.retryable) {
                Spacer(Modifier.width(10.dp))
                Text(
                    if (status.retryable) status.retryLabel else "Stop",
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = if (status.retryable) onRetry else onStop).padding(horizontal = 10.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
        }
        status.progress?.let { fraction ->
            val animated by animateFloatAsState(fraction, tween(300), label = "capsuleProgress")
            LinearProgressIndicator(progress = { animated }, Modifier.fillMaxWidth().height(2.dp), color = status.mood.color(), trackColor = Color.Transparent, drawStopIndicator = {})
        }
    }
}

private const val CAPSULE_OPEN_MS = 4_000L

/** The slim strip shown above the Camera, Library and Settings tabs. */
@Composable
fun StatusStrip(status: LinkStatus, onStop: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status.mood)
                Spacer(Modifier.width(10.dp))
                // Two lines, because the detail is the point: it says what is happening and what to do.
                // Fades only when the words change: a new count or figure just rolls its own digits.
                AnimatedContent(status.fadeKey, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "strip", modifier = Modifier.weight(1f)) {
                    Column {
                        RollingText(status.title, MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.onSurface)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(status.detail, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (status.amount == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                            status.amount?.let {
                                Spacer(Modifier.width(8.dp))
                                RollingText(it, MaterialTheme.typography.bodySmall, MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (status.stoppable) TextButton(onClick = onStop, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Stop") }
                if (status.retryable) TextButton(onClick = onRetry, Modifier.height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(status.retryLabel) }
            }
            Box { StatusProgress(status) }
        }
    }
}

@Composable
fun StatusIcon(status: LinkStatus, modifier: Modifier = Modifier) {
    val color by animateColorAsState(status.mood.color(), tween(300), label = "icon")
    AnimatedContent(status.icon, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "statusIcon") {
        Icon(it, null, modifier, tint = color)
    }
}
