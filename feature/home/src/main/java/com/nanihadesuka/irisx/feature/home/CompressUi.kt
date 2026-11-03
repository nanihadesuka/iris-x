package com.nanihadesuka.irisx.feature.home

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.domain.model.CompressionMode
import com.nanihadesuka.irisx.domain.model.CompressionProgress
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.model.estimatedCompressedSize

/** Asks first, because the phone copy is replaced. The returned function takes the photos to compress. */
@Composable
fun rememberCompress(viewModel: LibraryViewModel): (List<SavedPhoto>) -> Unit {
    val messages = LocalAppMessages.current
    val settings by viewModel.settings.collectAsStateWithLifecycle(Settings())
    var asking by remember { mutableStateOf<List<SavedPhoto>>(emptyList()) }
    var confirmed by remember { mutableStateOf<List<SavedPhoto>>(emptyList()) }
    var previewAfterGrant by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf<List<SavedPhoto>>(emptyList()) }

    // Without this the system hides the location from what Iris X reads, and it would be lost from the smaller file.
    val allowLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            !granted -> messages.show("Not compressed: photos would lose their location")
            previewAfterGrant -> previewing = confirmed
            else -> viewModel.compress(confirmed)
        }
        confirmed = emptyList()
    }

    if (asking.isNotEmpty()) {
        val photos = asking
        val before = photos.sumOf { it.sizeBytes }
        val after = photos.sumOf { settings.estimatedCompressedSize(it.sizeBytes) }
        AlertDialog(
            onDismissRequest = { asking = emptyList() },
            title = { Text(if (photos.size == 1) "Compress this photo?" else "Compress ${photos.size} photos?") },
            text = {
                Text(
                    "${settings.compressionSummary()}. Saves about ${fileSize((before - after).coerceAtLeast(0))}.\n\n" +
                        "This replaces the phone copy and cannot be undone here. The camera's copy is not touched. Location and shooting details are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmed = photos
                    previewAfterGrant = false
                    asking = emptyList()
                    allowLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }) { Text("Compress") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { asking = emptyList() }) { Text("Cancel") }
                    TextButton(onClick = {
                        confirmed = photos
                        previewAfterGrant = true
                        asking = emptyList()
                        allowLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }) { Text("Preview") }
                }
            },
        )
    }

    if (previewing.isNotEmpty()) {
        val photos = previewing
        CompressPreviewDialog(
            photos = photos,
            settings = settings,
            load = viewModel::previewCompression,
            onCancel = { previewing = emptyList() },
            onConfirm = { custom ->
                viewModel.compress(photos, custom)
                previewing = emptyList()
            },
        )
    }

    val progress by viewModel.compression.collectAsStateWithLifecycle()
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(progress.running) {
        if (wasRunning && !progress.running) messages.show(progress.summary())
        wasRunning = progress.running
    }
    return { asking = it }
}

private fun Settings.compressionSummary() = when (compressionMode) {
    CompressionMode.TargetSize -> "Aiming for about ${"%.1f".format(compressionTargetKb / 1024f)} MB each"
    CompressionMode.Percent -> "Reducing each by about $compressionPercent%"
    CompressionMode.Manual -> "Up to ${compressionMaxEdge} px at quality $compressionQuality"
}

private fun CompressionProgress.summary() = buildString {
    append("Compression saved ${fileSize(savedBytes)}")
    if (skipped > 0) append(", $skipped already small enough")
    if (failed > 0) append(", $failed could not be changed")
}

@Composable
fun CompressionStrip(progress: CompressionProgress) {
    if (!progress.running) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(
            "Compressing ${progress.done + 1} of ${progress.total}, ${fileSize(progress.savedBytes)} saved",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { progress.done.toFloat() / progress.total.coerceAtLeast(1) },
            Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
            drawStopIndicator = {},
        )
    }
}
