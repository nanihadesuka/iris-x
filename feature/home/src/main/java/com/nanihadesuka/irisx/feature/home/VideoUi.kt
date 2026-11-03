package com.nanihadesuka.irisx.feature.home

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.VideoJob
import com.nanihadesuka.irisx.domain.model.VideoPreset

/** The player exists only for the page on screen; other pages show the thumbnail. A tap shows controls and tools together. */
@OptIn(UnstableApi::class)
@Composable
fun VideoPage(video: SavedPhoto, active: Boolean, onControls: (Boolean) -> Unit) {
    val context = LocalContext.current
    val controls by rememberUpdatedState(onControls)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!active) {
            PhoneThumb(video.uri, video.name, Modifier.fillMaxSize(), sizePx = 720, contentScale = ContentScale.Fit)
        } else {
            val player = remember(video.uri) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(video.uri))
                    prepare()
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        this.player = player
                        controllerShowTimeoutMs = 0
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controls(visibility == android.view.View.VISIBLE) })
                    }
                },
                update = { it.player = player },
                // Keeps the player's bar above the viewer's tools.
                modifier = Modifier.fillMaxSize().padding(bottom = 88.dp),
            )
        }
    }
}

/** How small, with an estimate. [onChoose] starts the work. */
@Composable
fun VideoPresetDialog(video: SavedPhoto, forBackup: Boolean, onDismiss: () -> Unit, onChoose: (VideoPreset) -> Unit) {
    var preset by remember { mutableStateOf(VideoPreset.Good) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (forBackup) "Compress before backup" else "Compress this video") },
        text = {
            Column {
                VideoPreset.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable { preset = option }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(preset == option, { preset = option })
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text("About ${fileSize(option.estimatedBytes(video.durationMillis).coerceAtMost(video.sizeBytes))}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    if (forBackup) "Only the smaller copy goes to Google Photos. The original stays on this phone." else "Saved as a new file. The original stays on this phone.",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onChoose(preset) }) { Text("Compress") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Progress with Cancel while running. Says how it ended, once. */
@Composable
fun VideoJobStrip(job: VideoJob?, onCancel: () -> Unit, onSeen: () -> Unit, modifier: Modifier = Modifier) {
    val messages = LocalAppMessages.current
    LaunchedEffect(job?.running) {
        if (job != null && !job.running) {
            val message = job.error ?: if (job.forBackup) "Smaller video queued for Google Photos backup" else "Smaller video saved"
            messages.show(message)
            onSeen()
        }
    }
    if (job == null || !job.running) return
    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Compressing ${job.name}, ${job.percent}%", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = Color.White, maxLines = 1)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        LinearProgressIndicator(progress = { job.percent / 100f }, Modifier.fillMaxWidth().height(3.dp), drawStopIndicator = {})
    }
}
