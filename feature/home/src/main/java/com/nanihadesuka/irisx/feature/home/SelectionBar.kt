package com.nanihadesuka.irisx.feature.home

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nanihadesuka.irisx.domain.model.smallVideoName
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import kotlinx.coroutines.launch

/** The actions for a multi-selection, laid out like the viewer's tools. Each screen fills it with what makes sense there. */
@Composable
fun SelectionBar(visible: Boolean, tools: @Composable () -> Unit) {
    AnimatedVisibility(
        visible,
        enter = expandVertically(tween(SELECTION_FADE_MS)) + fadeIn(tween(SELECTION_FADE_MS)),
        exit = shrinkVertically(tween(SELECTION_FADE_MS)) + fadeOut(tween(SELECTION_FADE_MS)),
    ) {
        Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) { tools() }
        }
    }
}

/** Google Photos backs up the folder by itself. It can only be offered the folder once a photo is in it. */
@Composable
fun BackupExplainDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Turn on backup once") },
        text = {
            Text("In Google Photos, tap your profile picture, then Photos settings, Backup, Back up device folders, and turn on Iris X Backup. Photos you send there then upload by themselves. Iris X cannot see when an upload has finished.")
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                openGooglePhotos(context) { messages.show("Google Photos is not installed") }
            }) { Text("Open Google Photos") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

/** Queues photos for backup, one at a time. Files saved by an earlier install ask the system once for all that remain. */
@Composable
fun rememberBatchBackup(library: LibraryViewModel): (List<SavedPhoto>) -> Unit {
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    val scope = rememberCoroutineScope()
    var explain by rememberSaveable { mutableStateOf(false) }
    var waiting by remember { mutableStateOf(emptyList<SavedPhoto>()) }
    var queuedSoFar by remember { mutableStateOf(0) }
    var firstEver by remember { mutableStateOf(false) }
    if (explain) BackupExplainDialog { explain = false }

    fun finished() {
        if (firstEver) explain = true
        else if (queuedSoFar > 0) messages.show("$queuedSoFar queued for Google Photos backup")
    }

    lateinit var allow: (android.content.IntentSender) -> Unit
    fun queue(photos: List<SavedPhoto>, mayAsk: Boolean) {
        scope.launch {
            for ((index, photo) in photos.withIndex()) {
                try {
                    library.moveToBackup(photo.uri)
                    queuedSoFar++
                } catch (e: SecurityException) {
                    val rest = photos.drop(index)
                    if (mayAsk) {
                        waiting = rest
                        allow(MediaStore.createWriteRequest(context.contentResolver, rest.map { Uri.parse(it.uri) }).intentSender)
                        return@launch
                    }
                    messages.show("${rest.size} could not be queued")
                    break
                }
            }
            finished()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val rest = waiting
        waiting = emptyList()
        if (result.resultCode == Activity.RESULT_OK) queue(rest, mayAsk = false) else finished()
    }
    allow = { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
    return { photos ->
        firstEver = library.photos.value.orEmpty().none { it.queuedForBackup }
        queuedSoFar = 0
        queue(photos, mayAsk = true)
    }
}

/** Names of the photos in the backup folder. A full-size video counts when its smaller copy is there. */
fun backupNames(photos: List<SavedPhoto>): Set<String> = photos.filter { it.queuedForBackup }.mapTo(HashSet()) { it.name.lowercase() }

fun Set<String>.holds(name: String) = name.lowercase() in this || smallVideoName(name).lowercase() in this

/** The small marks in a grid cell's corner: on this phone, and in the folder Google Photos backs up. */
@Composable
fun PhotoMarks(downloaded: Boolean, inBackup: Boolean, modifier: Modifier = Modifier) {
    if (!downloaded && !inBackup) return
    Row(modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (inBackup) Mark(Icons.Rounded.CloudDone, "In the Google Photos backup folder")
        if (downloaded) Mark(Icons.Rounded.DownloadDone, "On this phone")
    }
}

@Composable
private fun Mark(icon: ImageVector, description: String) {
    Icon(icon, description, Modifier.size(20.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(3.dp), tint = Color.White)
}

private const val SELECTION_FADE_MS = 180
