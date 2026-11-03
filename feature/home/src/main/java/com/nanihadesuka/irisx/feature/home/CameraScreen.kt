package com.nanihadesuka.irisx.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download
import com.nanihadesuka.irisx.domain.model.groupPhotos
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.core.designsystem.StatusColors
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.domain.model.CatalogPhase
import com.nanihadesuka.irisx.domain.model.ConnectionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.koin.androidx.compose.koinViewModel

@Composable
fun CameraRoute(hasCamera: Boolean, onPair: () -> Unit, onOpenPhoto: (Int, MediaFilter) -> Unit, viewModel: CameraViewModel = koinViewModel(), library: LibraryViewModel = koinViewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by library.photos.collectAsStateWithLifecycle()
    val messages = LocalAppMessages.current
    var selectedHandlesToClear by remember { mutableStateOf(false) }
    val delete = rememberPhoneDelete {
        selectedHandlesToClear = true
        messages.show("Removed from the phone. Still on the camera")
    }
    val ui by viewModel.state.collectAsStateWithLifecycle()
    // Handles only: the selection survives rotation and entries stay in the browser.
    var selectedHandles by rememberSaveable { mutableStateOf(listOf<Int>()) }
    if (selectedHandlesToClear) {
        selectedHandles = emptyList()
        selectedHandlesToClear = false
    }
    val selected = selectedHandles.toSet()
    val catalog = ui.catalog
    val entries = catalog.entries
    val canReach = ui.connection == ConnectionState.Connected
    val byHandle = remember(entries) { entries.associateBy { it.handle } }
    var filter by rememberSaveable { mutableStateOf(MediaFilter.All) }
    val shownHandles = remember(catalog.handles, byHandle, filter) { catalog.handles.filter { filter.accepts(byHandle[it]?.isVideo) } }
    // Older cache entries have no capture time until the next browse, but a photo on the phone knows its own.
    val phoneTimes = remember(saved) { saved.orEmpty().filter { it.takenAtMillis > 0 }.associate { it.name.lowercase() to it.takenAtMillis } }
    val timeOf: (Int) -> Long? = { h -> byHandle[h]?.let { it.capturedMillis ?: phoneTimes[it.name.lowercase()] } }
    val groups = remember(shownHandles, byHandle, ui.grouping, phoneTimes) { groupPhotos(shownHandles, timeOf, ui.grouping) }
    val undated = remember(shownHandles, byHandle, phoneTimes) { shownHandles.count { timeOf(it) == null } }
    val inBackup = remember(saved) { backupNames(saved.orEmpty()) }
    val grid = rememberLazyGridState()
    val dragGesture = remember { DragSelectGesture() }
    // The grid follows its first photo when sections rearrange, which would leave a new visit partway down the card.
    // Until the user scrolls, it stays at the top; after that it keeps their place between tabs.
    var scrolledByUser by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(grid) {
        grid.interactionSource.interactions.first { it is androidx.compose.foundation.interaction.DragInteraction.Start }
        scrolledByUser = true
    }
    LaunchedEffect(groups) { if (!scrolledByUser && selected.isEmpty()) grid.scrollToItem(0) }
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Int } }
            .distinctUntilChanged()
            .collect(viewModel::prioritize)
    }

    val working = catalog.phase == CatalogPhase.Connecting || catalog.phase == CatalogPhase.Reading

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("On camera", style = MaterialTheme.typography.headlineSmall)
                Text(
                    when {
                        selected.isNotEmpty() -> "${selected.size} selected"
                        undated > 0 && !working && catalog.phase != CatalogPhase.Live -> "$undated need their date. Tap refresh to sort them by day"
                        catalog.phase == CatalogPhase.Idle && entries.isNotEmpty() -> "${entries.size} from your last visit, refresh for new ones"
                        catalog.total != null -> "${catalog.total} on the card, ${ui.missing.size} not on this phone. Tap to save and view"
                        else -> "Copies only, nothing is deleted from the camera"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected.isEmpty() && entries.isNotEmpty() && !working && catalog.phase != CatalogPhase.Live) {
                IconButton(onClick = viewModel::browse) { Icon(Icons.Rounded.Refresh, "Refresh from the camera") }
            }
            if (selected.isEmpty()) {
                val all = ui.missing
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }, enabled = all.isNotEmpty()) { Icon(Icons.Rounded.MoreVert, "More options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Download all ${all.size} not on phone") },
                            enabled = catalog.download == null && !working && canReach,
                            onClick = {
                                menu = false
                                viewModel.download(all)
                            },
                        )
                    }
                }
            }
        }

        MediaFilterRow(filter, { filter = it })
        AnimatedVisibility(catalog.phase == CatalogPhase.Reading) {
            val total = catalog.total ?: 0
            Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                Text("Reading the card, ${entries.size} of $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { entries.size.toFloat() / total.coerceAtLeast(1) },
                    Modifier.fillMaxWidth().height(3.dp),
                    drawStopIndicator = {},
                )
            }
        }
        catalog.message?.let { message ->
            Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    Modifier.weight(1f).padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (catalog.phase == CatalogPhase.Failed) StatusColors.Attention else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (catalog.phase == CatalogPhase.Failed) Button(onClick = viewModel::browse) { Text("Retry") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (catalog.handles.isEmpty() && catalog.incoming == null) {
                EmptyCamera(hasCamera, canReach, catalog.phase, onPair, viewModel::browse)
            } else {
                LazyVerticalGrid(
                    modifier = Modifier.dragSelect(
                        grid,
                        dragGesture,
                        order = groups.flatMap { it.items }.filter { h -> byHandle[h]?.let { it.isJpeg || it.isVideo } == true },
                        selected = selectedHandles,
                        onChange = { selectedHandles = it },
                    ),
                    state = grid,
                    columns = GridCells.Adaptive(92.dp),
                    contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 4.dp, bottom = 72.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // New photos go first, because the card lists newest first.
                    catalog.incoming?.let { left ->
                        items(left.coerceAtLeast(1), key = { "incoming:$it" }) {
                            Box(Modifier.animateItem().aspectRatio(1f), contentAlignment = Alignment.Center) {
                                Shimmer(Modifier.fillMaxSize().clip(MaterialTheme.shapes.small))
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                    groups.forEach { group ->
                    // Videos only download when asked, so a whole section means its photos unless the filter says videos.
                    val selectable = group.items.filter { h -> byHandle[h]?.let { if (filter == MediaFilter.Videos) it.isVideo else it.isJpeg } == true }
                    item(key = "section:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                        val all = selectable.isNotEmpty() && selectable.all { it in selected }
                        SectionHeader(group.title, group.items.size, all, onToggle = {
                            selectedHandles = if (all) selectedHandles - selectable.toSet() else (selectedHandles + selectable).distinct()
                        })
                    }
                    items(group.items, key = { it }) { handle ->
                        val entry = byHandle[handle]
                        if (entry == null) {
                            ThumbPlaceholder(animated = working, modifier = Modifier.animateItem().clickable { onOpenPhoto(handle, filter) })
                        } else CatalogCell(
                            entry = entry,
                            square = ui.squareGrid,
                            phoneUri = ui.onPhone[entry.name.lowercase()],
                            inBackup = inBackup.holds(entry.name),
                            selected = entry.handle in selected,
                            selecting = selected.isNotEmpty(),
                            downloadingHd = catalog.download?.currentName == entry.name,
                            onOpen = { if (!dragGesture.active) onOpenPhoto(entry.handle, filter) },
                            onToggle = {
                                if (!dragGesture.active) selectedHandles = if (entry.handle in selected) selectedHandles - entry.handle else selectedHandles + entry.handle
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    }
                }
            }

            GetButton(
                visible = selected.isEmpty() && ui.newPhotos.isNotEmpty() && catalog.download == null && !working && canReach,
                label = "Get ${ui.newPhotos.size} new",
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                onClick = { viewModel.download(ui.newPhotos) },
            )
        }

        // Here the point is getting photos off the camera. Compressing belongs to the Library; nothing here touches the card.
        val chosen = entries.filter { it.handle in selected }
        val names = chosen.mapTo(HashSet()) { it.name.lowercase() }
        val phoneCopies = saved.orEmpty().filter { it.name.lowercase() in names }
        SelectionBar(selected.isNotEmpty()) {
            Tool(Icons.Rounded.Download, "Download", chosen.any { it.name.lowercase() !in ui.onPhone }) {
                if (canReach) {
                    viewModel.download(chosen)
                    selectedHandles = emptyList()
                } else {
                    messages.show("Connect the camera to download")
                }
            }
            Tool(Icons.Rounded.Share, "Share", phoneCopies.isNotEmpty()) { sharePhotos(context, phoneCopies.map { it.uri }) }
            Tool(Icons.Rounded.DeleteOutline, "From phone", phoneCopies.isNotEmpty()) {
                viewModel.keepPreviews(phoneCopies.map { it.name })
                delete(phoneCopies.map { it.uri })
            }
            Tool(Icons.Rounded.Close, "Clear", true) { selectedHandles = emptyList() }
        }
    }
    }
}

/** Says the original is on its way, on a grid cell and in the preview. */
@Composable
private fun HdBadge(label: String = "HD") {
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.65f), MaterialTheme.shapes.small).padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(10.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun GetButton(visible: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
    ) {
        ExtendedFloatingActionButton(onClick = onClick, icon = { Icon(Icons.Rounded.Download, null) }, text = { Text(label) })
    }
}

@Composable
private fun EmptyCamera(hasCamera: Boolean, canReach: Boolean, phase: CatalogPhase, onPair: () -> Unit, onBrowse: () -> Unit) {
    val working = phase == CatalogPhase.Connecting || phase == CatalogPhase.Reading
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        Icon(if (working) Icons.Rounded.CloudDownload else Icons.Rounded.CameraAlt, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            when {
                !hasCamera -> "No camera paired"
                working -> "Connecting to the camera"
                canReach -> "See what is on the card"
                else -> "The camera is not connected"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                !hasCamera -> "Pair your camera to browse its photos."
                working -> "This takes a few seconds. Thumbnails appear as they are read."
                canReach -> "Your phone joins the camera's Wi-Fi while browsing, then returns to its own network."
                else -> "Turn the camera on and tap below. Iris X reconnects, then browses."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        when {
            !hasCamera -> Button(onClick = onPair) { Text("Pair camera") }
            !working -> Button(onClick = onBrowse) { Text(if (phase == CatalogPhase.Failed) "Retry" else if (canReach) "Browse camera" else "Connect and browse") }
        }
    }
}

/** A photo the card listed whose thumbnail has not been read. It only shimmers while that can still happen. */
@Composable
private fun ThumbPlaceholder(animated: Boolean, modifier: Modifier) {
    val cell = modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small)
    if (animated) Shimmer(cell) else Box(cell.background(MaterialTheme.colorScheme.surfaceContainer))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CatalogCell(
    entry: CatalogEntry,
    square: Boolean,
    phoneUri: String?,
    inBackup: Boolean,
    selected: Boolean,
    selecting: Boolean,
    downloadingHd: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inset by animateDpAsState(if (selected) 6.dp else 0.dp, tween(150), label = "inset")
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, tween(150), label = "border")
    val shape = MaterialTheme.shapes.small
    Box(
        modifier.aspectRatio(1f).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(
            enabled = entry.isJpeg || entry.isVideo,
            onClick = { if (selecting) onToggle() else onOpen() },
        ),
    ) {
        // A photo on the phone shows that copy: it is sharper, and its rotation is known even with no camera connected.
        val thumb = Modifier.fillMaxSize().padding(inset).clip(shape).border(2.dp, border, shape)
        val scale = if (square) ContentScale.Crop else ContentScale.Fit
        if (phoneUri != null) {
            PhoneThumb(phoneUri, entry.name, thumb, contentScale = scale, sharedKey = "h${entry.handle}")
        } else {
            CameraThumb(entry, thumb, contentScale = scale, sharedKey = "h${entry.handle}")
        }
        if (!entry.isJpeg) {
            Text(
                entry.name.substringAfterLast('.', "").uppercase(),
                Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = 0.6f), shape).padding(horizontal = 5.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
        AnimatedVisibility(downloadingHd, Modifier.align(Alignment.TopEnd).padding(3.dp), enter = fadeIn(), exit = fadeOut()) { HdBadge() }
        PhotoMarks(downloaded = phoneUri != null, inBackup = inBackup, Modifier.align(Alignment.BottomEnd))
    }
}
