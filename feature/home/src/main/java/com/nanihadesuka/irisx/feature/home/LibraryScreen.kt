package com.nanihadesuka.irisx.feature.home

import android.text.format.DateUtils
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import androidx.compose.foundation.clickable
import kotlinx.coroutines.flow.map
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryRoute(onOpenPhoto: (String, MediaFilter) -> Unit, hiddenPhotos: Boolean, onAllowHidden: () -> Unit, viewModel: LibraryViewModel = koinViewModel()) {
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val square by remember(viewModel) { viewModel.settings.map { it.squareGrid } }.collectAsStateWithLifecycle(true)
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val delete = rememberPhoneDelete { selected = emptyList() }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.All) }
    val grouping by remember(viewModel) { viewModel.settings.map { it.photoGrouping } }.collectAsStateWithLifecycle(com.nanihadesuka.irisx.domain.model.PhotoGrouping.Days)
    val groups = remember(photos, filter, grouping) {
        com.nanihadesuka.irisx.domain.model.groupPhotos(
            photos.orEmpty().filter { filter.accepts(it.isVideo) },
            { p: SavedPhoto -> p.takenAtMillis.takeIf { it > 0 } ?: p.savedAtMillis },
            grouping,
        )
    }
    val list = photos.orEmpty()
    val inBackup = remember(photos) { backupNames(list) }
    val compress = rememberCompress(viewModel)
    val backup = rememberBatchBackup(viewModel)
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val dragGesture = remember { DragSelectGesture() }
    val messages = LocalAppMessages.current
    val compression by viewModel.compression.collectAsStateWithLifecycle()
    val videoJob by viewModel.videoJob.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (selected.isEmpty()) "On phone" else "${selected.size} selected", style = MaterialTheme.typography.headlineSmall)
                if (selected.isEmpty()) {
                    Text(
                        "${list.size} photos, ${fileSize(list.sumOf { it.sizeBytes })}, in Pictures/Iris X",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selected.isEmpty()) {
                val all = list.filter { !it.compressed && !it.isVideo }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, enabled = all.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Compress all ${all.size}") },
                            onClick = {
                                menu = false
                                compress(all)
                            },
                        )
                    }
                }
            }
        }

        MediaFilterRow(filter, { filter = it })
        CompressionStrip(compression)
        VideoJobStrip(videoJob, viewModel::cancelVideoJob, viewModel::clearVideoJob)
        if (hiddenPhotos) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Photos from your earlier install are hidden until you allow access.",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onAllowHidden) { Text("Allow") }
            }
        }
        if (photos != null && list.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
                Text("Nothing downloaded yet", style = MaterialTheme.typography.titleMedium)
                Text("Photos from the camera collect here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                modifier = Modifier.weight(1f).dragSelect(grid, dragGesture, groups.flatMap { g -> g.items.map { it.uri } }, selected, { selected = it }),
                state = grid,
                columns = GridCells.Adaptive(92.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                groups.forEach { group ->
                    item(key = "day:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                        val uris = group.items.map { it.uri }
                        val all = uris.all { it in selected }
                        SectionHeader(group.title, uris.size, all, onToggle = {
                            selected = if (all) selected - uris.toSet() else (selected + uris).distinct()
                        })
                    }
                    items(group.items, key = { it.uri }) { photo ->
                        LibraryCell(
                            square = square,
                            photo = photo,
                            inBackup = inBackup.holds(photo.name),
                            selected = photo.uri in selected,
                            modifier = Modifier.animateItem().clickable(
                                onClick = {
                                    when {
                                        dragGesture.active -> Unit
                                        selected.isEmpty() -> onOpenPhoto(photo.uri, filter)
                                        photo.uri in selected -> selected = selected - photo.uri
                                        else -> selected = selected + photo.uri
                                    }
                                },
                            ),
                        )
                    }
                }
            }
        }

        // Here the point is looking after the phone's copies.
        val chosen = list.filter { it.uri in selected }
        val toCompress = chosen.filter { !it.compressed && !it.isVideo }
        val toBackUp = chosen.filter { !it.isVideo && !it.queuedForBackup }
        SelectionBar(selected.isNotEmpty()) {
            Tool(Icons.Rounded.Share, "Share", true) { sharePhotos(context, selected) }
            Tool(Icons.Rounded.CloudUpload, "Photos", toBackUp.isNotEmpty()) {
                if (toBackUp.size < chosen.size) messages.show("Queueing ${toBackUp.size}, skipping ${chosen.size - toBackUp.size}")
                backup(toBackUp)
                selected = emptyList()
            }
            Tool(Icons.Rounded.Compress, "Compress", toCompress.isNotEmpty()) {
                if (toCompress.size < chosen.size) messages.show("Compressing ${toCompress.size}, skipping ${chosen.size - toCompress.size}")
                compress(toCompress)
                selected = emptyList()
            }
            Tool(Icons.Rounded.DeleteOutline, "Delete", true) {
                viewModel.keepPreviews(chosen.map { it.name })
                delete(selected)
            }
            Tool(Icons.Rounded.Close, "Clear", true) { selected = emptyList() }
        }
    }
}

@Composable
private fun LibraryCell(photo: SavedPhoto, selected: Boolean, square: Boolean, inBackup: Boolean, modifier: Modifier) {
    val inset by animateDpAsState(if (selected) 6.dp else 0.dp, tween(150), label = "inset")
    val shape = MaterialTheme.shapes.small
    Box(modifier.aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        PhoneThumb(
            photo.uri,
            photo.name,
            Modifier.fillMaxSize().padding(inset).clip(shape).border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape),
            contentScale = if (square) ContentScale.Crop else ContentScale.Fit,
            sharedKey = photo.uri,
        )
        if (photo.isVideo) {
            Icon(Icons.Rounded.PlayCircle, "Video", Modifier.align(Alignment.Center).size(30.dp), tint = Color.White.copy(alpha = 0.9f))
        }
        // Everything here is on the phone, so only the backup needs a mark.
        PhotoMarks(downloaded = false, inBackup = inBackup, Modifier.align(Alignment.BottomEnd))
    }
}
