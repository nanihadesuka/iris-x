package com.nanihadesuka.irisx.feature.home

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nanihadesuka.irisx.core.designsystem.StatusColors
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.domain.model.CatalogPhase
import com.nanihadesuka.irisx.domain.model.CatalogView
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.ViewStage
import com.nanihadesuka.irisx.domain.model.smallVideoName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.DoubleClickToZoomListener
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs

/** What the viewer opened on: a phone photo's uri, or a camera handle so photos not yet read can be opened too. */
data class ViewerTarget(val fromCamera: Boolean, val key: String, val filter: MediaFilter = MediaFilter.All)

private class Page(val key: String, val name: String, val phone: SavedPhoto?, val handle: Int?, val entry: CatalogEntry?)

/**
 * Immersive view for Home, Camera and Library: only the photo, with system bars hidden. A tap shows the tools and they
 * stay while swiping to other photos; another tap hides them. Swiping down closes it, back to the photo's spot. Camera photos start on the best image at hand and say so.
 */
@Composable
fun PhotoViewer(
    target: ViewerTarget,
    onClosed: () -> Unit,
    library: LibraryViewModel = koinViewModel(),
    camera: CameraViewModel = koinViewModel(),
    onNeedsCamera: (Boolean) -> Unit = {},
    onToolsShown: (Boolean) -> Unit = {},
) {
    val photos by library.photos.collectAsStateWithLifecycle()
    val cameraUi by camera.state.collectAsStateWithLifecycle()
    val catalog = cameraUi.catalog
    val saved = photos ?: return
    val startKey = if (target.fromCamera) "h${target.key}" else target.key
    val pages = remember(target.fromCamera, target.filter, saved, catalog.handles, catalog.entries) {
        if (target.fromCamera) {
            val phoneByName = saved.associateBy { it.name.lowercase() }
            val entries = catalog.entries.associateBy { it.handle }
            catalog.handles.map { handle ->
                val entry = entries[handle]
                Page("h$handle", entry?.name ?: "Photo not read yet", entry?.let { phoneByName[it.name.lowercase()] }, handle, entry)
            }
        } else {
            saved.map { Page(it.uri, it.name, it, null, null) }
        }.filter { page -> target.filter.accepts(page.entry?.isVideo ?: page.phone?.isVideo) }
    }
    if (pages.isEmpty()) {
        LaunchedEffect(Unit) { onClosed() }
        return
    }
    val pager = rememberPagerState(initialPage = pages.indexOfFirst { it.key == startKey }.coerceAtLeast(0)) { pages.size }
    val context = LocalContext.current
    val messages = LocalAppMessages.current
    val delete = rememberPhoneDelete { messages.show(if (target.fromCamera) "Removed from the phone. Still on the camera" else "Deleted from the phone") }
    var tools by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val sources = LocalPhotoSources.current
    val scope = rememberCoroutineScope()
    // A photo left before its original arrived frees the camera for the next request.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (target.fromCamera) camera.stopViewing() } }
    val progress = remember { Animatable(0f) }
    val dragY = remember { Animatable(0f) }
    val ghostAlpha = remember { Animatable(1f) }
    var closing by remember { mutableStateOf(false) }
    var heightPx by remember { mutableFloatStateOf(1f) }
    val dragFraction = (dragY.value / (heightPx * DRAG_FULL)).coerceIn(0f, 1f)
    val dragScale = 1f - dragFraction * DRAG_SHRINK
    fun sourceFlight(key: String, scale: Float = 1f, dy: Float = 0f): Flight? {
        val source = sources[key] ?: return null
        val bitmap = source.bitmap ?: return null
        return if (source.bounds.isEmpty) null else Flight(bitmap, source.bounds, scale, dy, source.crop)
    }
    var flight by remember { mutableStateOf(sourceFlight(startKey)) }
    val current = pages.getOrNull(pager.currentPage) ?: pages.last()
    val settled = pages.getOrNull(pager.settledPage) ?: pages.last()
    val canReach = cameraUi.connection == ConnectionState.Connected
    LaunchedEffect(tools) { onToolsShown(tools) }
    LaunchedEffect(current.handle != null && current.phone == null) { onNeedsCamera(current.handle != null && current.phone == null) }
    val view = catalog.viewing?.takeIf { it.handle == current.handle }

    // Viewing a camera photo saves it, but only once the swipe stops on it.
    LaunchedEffect(settled.key, settled.entry != null, settled.phone == null, canReach) {
        if (target.fromCamera) camera.viewingOnly(settled.handle)
        if (settled.phone != null || settled.handle == null) return@LaunchedEffect
        val entry = settled.entry
        if (entry == null) {
            if (!canReach) return@LaunchedEffect
            camera.prioritize(listOf(settled.handle))
            camera.browse()
        } else if (entry.isJpeg) { // a video may be large, so it only downloads when asked
            delay(SETTLE_MS)
            val viewing = camera.state.value.catalog.viewing
            if (viewing?.handle != entry.handle || viewing.stage == ViewStage.Failed) camera.view(entry, network = canReach)
        }
    }

    // The preview is sharp at screen size; zooming in is what asks for the original.
    LaunchedEffect(zoomed, settled.key) {
        val entry = settled.entry
        if (zoomed && settled.phone == null && entry?.isJpeg == true && canReach) camera.download(listOf(entry))
    }

    // Opening: the photo grows from its spot, then hands over to the real page once that has drawn.
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(TRAVEL_MS, easing = FastOutSlowInEasing))
        withTimeoutOrNull(HANDOVER_MAX_MS) { snapshotFlow { ready }.first { it } }
        if (!closing) {
            ghostAlpha.animateTo(0f, tween(FADE_MS))
            if (!closing) flight = null
        }
    }
    val pageKey by rememberUpdatedState(current.key)
    fun close() {
        if (closing) return
        closing = true
        tools = false
        scope.launch {
            // Back to wherever the photo on screen now lives; a photo with no spot there just fades.
            ghostAlpha.snapTo(1f)
            flight = sourceFlight(pageKey, 1f - (dragY.value / (heightPx * DRAG_FULL)).coerceIn(0f, 1f) * DRAG_SHRINK, dragY.value)
            progress.animateTo(0f, tween(TRAVEL_MS, easing = FastOutSlowInEasing))
            onClosed()
        }
    }
    BackHandler { close() }

    // Google Photos backs up the folder by itself. It can only be offered the folder once a photo is in it.
    var explainBackup by rememberSaveable { mutableStateOf(false) }
    var pendingBackup by remember { mutableStateOf<SavedPhoto?>(null) }
    var preparingBackup by remember { mutableStateOf<String?>(null) }
    fun moveToBackup(photo: SavedPhoto, recover: (SavedPhoto, android.content.IntentSender) -> Unit) {
        val first = saved.none { it.queuedForBackup }
        scope.launch {
            try {
                preparingBackup = photo.uri
                library.moveToBackup(photo.uri)
                if (first) explainBackup = true
                else messages.show("Queued for Google Photos backup")
            } catch (e: SecurityException) {
                recover(photo, MediaStore.createWriteRequest(context.contentResolver, listOf(Uri.parse(photo.uri))).intentSender)
            } finally {
                preparingBackup = null
            }
        }
    }
    // A file saved by an earlier install needs the system's permission first.
    val allowMove = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val photo = pendingBackup
        pendingBackup = null
        if (result.resultCode == Activity.RESULT_OK && photo != null) moveToBackup(photo) { _, _ -> }
    }
    val queueBackup: (SavedPhoto) -> Unit = { photo ->
        moveToBackup(photo) { denied, sender ->
            pendingBackup = denied
            allowMove.launch(IntentSenderRequest.Builder(sender).build())
        }
    }
    if (explainBackup) BackupExplainDialog { explainBackup = false }

    val compress = rememberCompress(library)
    var videoChoice by remember { mutableStateOf<Pair<SavedPhoto, Boolean>?>(null) }
    val videoJob by library.videoJob.collectAsStateWithLifecycle()
    val finishing by library.finishing.collectAsStateWithLifecycle()
    videoChoice?.let { (video, forBackup) ->
        VideoPresetDialog(video, forBackup, onDismiss = { videoChoice = null }) { preset ->
            library.compressVideo(video, preset, forBackup)
            videoChoice = null
        }
    }
    val activity = LocalActivity.current
    DisposableEffect(activity, tools, closing) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Status bar only: with the navigation bar hidden too, Android spends the first edge swipe on revealing it.
        if (tools || closing) bars?.show(WindowInsetsCompat.Type.statusBars()) else bars?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    val showTools = tools && dragFraction == 0f && !closing && progress.value == 1f
    // Solid black, except while travelling or dragging away, when the screen beneath is meant to show through.
    Box(
        Modifier.fillMaxSize().onSizeChanged { heightPx = it.height.toFloat() }
            .background(Color.Black.copy(alpha = (progress.value * (1f - dragFraction)).coerceIn(0f, 1f)))
            .pointerInput(zoomed) {
                // Seen before the pages: a clearly downward drag at normal size closes; anything else is left to them.
                if (zoomed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val velocity = VelocityTracker()
                    var moved = Offset.Zero
                    var dragging = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed || event.changes.count { it.pressed } > 1) break
                        val delta = change.positionChange()
                        if (!dragging) {
                            moved += delta
                            if (moved.getDistance() < viewConfiguration.touchSlop) continue
                            if (moved.y <= 0f || abs(moved.y) < abs(moved.x) * DOWN_BIAS) break
                            dragging = true
                        }
                        velocity.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        scope.launch { dragY.snapTo((dragY.value + delta.y).coerceAtLeast(0f)) }
                    }
                    if (dragging) {
                        if (dragY.value > heightPx * DISMISS_FRACTION || velocity.calculateVelocity().y > DISMISS_VELOCITY) {
                            close()
                        } else {
                            scope.launch { dragY.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
                        }
                    }
                }
            },
    ) {
        val hidePages = flight != null && progress.value < 1f || closing && flight != null
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = dragY.value
                scaleX = dragScale
                scaleY = dragScale
                alpha = if (hidePages) 0f else progress.value
            },
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1, // The photos either side load in the background, ready for the swipe.
            key = { pages[it].key },
        ) { index ->
            val page = pages[index]
            ViewerPage(
                page,
                view = catalog.viewing?.takeIf { it.handle == page.handle },
                active = index == pager.settledPage,
                arriving = catalog.download?.currentName == page.name || page.name in finishing,
                onTap = { tools = !tools },
                onZoomed = { if (index == pager.settledPage) zoomed = it },
                onControls = { tools = it },
                onReady = { if (pages[index].key == startKey) ready = true },
            )
        }
        flight?.let { PhotoFlight(it, progress = { progress.value }, alpha = { ghostAlpha.value }) }
        AnimatedVisibility(showTools, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text(current.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                Text("${pager.currentPage + 1} / ${pages.size}", Modifier.padding(end = 16.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            VideoJobStrip(videoJob, library::cancelVideoJob, library::clearVideoJob)
            // The capsule above the photo speaks for connecting, downloads and failures; this only adds what it does not say.
            val quiet = catalog.phase == CatalogPhase.Connecting || catalog.phase == CatalogPhase.Failed
            val loading = if (quiet) null else loadingLabel(current, view, canReach, catalog.download?.takeIf { current.phone == null && it.currentName == current.entry?.name } != null)
            // A preview that comes straight from the cache says nothing at all.
            var label by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
            LaunchedEffect(loading) {
                if (loading?.second == true && label == null) delay(LABEL_DELAY_MS)
                label = loading
            }
            AnimatedVisibility(label != null && dragFraction == 0f && !closing, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
                val shown = remember { mutableStateOf(label) }
                label?.let { shown.value = it }
                shown.value?.let { LoadingPill(it, Modifier.padding(bottom = 10.dp).then(if (showTools) Modifier else Modifier.navigationBarsPadding())) }
            }
            AnimatedVisibility(showTools, enter = fadeIn(tween(FADE_MS)), exit = fadeOut(tween(FADE_MS))) {
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding()) {
                    val phone = current.phone
                    if (phone != null) AnimatedVisibility(showInfo) { PhotoInfo(phone) }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        val entry = current.entry
                        val savable = phone == null && (entry?.isJpeg == true || entry?.isVideo == true)
                        val small = phone?.takeIf { it.isVideo && !it.compressed }?.let { video -> saved.firstOrNull { it.name == smallVideoName(video.name) } }
                        val queued = phone?.queuedForBackup == true || small?.queuedForBackup == true
                        val downloading = catalog.download?.currentName == entry?.name && entry != null
                        Tool(if (phone != null) Icons.Rounded.CheckCircle else Icons.Rounded.Download, if (phone != null) "Saved" else "Download", phone != null || savable, busy = phone == null && downloading) {
                            if (savable && entry != null) {
                                if (canReach) camera.download(listOf(entry)) else messages.show("Connect the camera to download")
                            }
                        }
                        Tool(Icons.Rounded.Share, "Share", phone != null) { phone?.let { sharePhotos(context, listOf(it.uri)) } }
                        Tool(
                            if (queued) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
                            if (queued) "Queued" else "Photos",
                            phone != null && !queued && preparingBackup == null && videoJob?.running != true,
                            busy = phone != null && preparingBackup == phone.uri,
                        ) {
                            when {
                                phone == null -> Unit
                                // A full-size video never goes to backup: its smaller copy does, made first if need be.
                                small != null -> queueBackup(small)
                                phone.isVideo && !phone.compressed -> videoChoice = phone to true
                                else -> queueBackup(phone)
                            }
                        }
                        Tool(
                            Icons.Rounded.Compress,
                            if (phone?.compressed == true || small != null) "Reduced" else "Compress",
                            phone != null && !phone.compressed && small == null && videoJob?.running != true,
                        ) {
                            phone?.let { if (it.isVideo) videoChoice = it to false else compress(listOf(it)) }
                        }
                        Tool(Icons.Rounded.Info, "Details", phone != null) { showInfo = !showInfo }
                        // The phone copy only: nothing here can reach the camera.
                        Tool(Icons.Rounded.DeleteOutline, "Delete", phone != null) { phone?.let {
                            camera.keepPreviews(listOf(it.name))
                            delete(listOf(it.uri))
                        } }
                    }
                }
            }
        }
    }
}

/** One line under a photo, only when it adds something: a preview being read, or why there will not be more. */
private fun loadingLabel(page: Page, view: CatalogView?, canReach: Boolean, receiving: Boolean): Pair<String, Boolean>? {
    val entry = page.entry
    return when {
        page.phone != null || page.handle == null || receiving -> null
        entry == null -> if (canReach) "Getting the preview" to true else null
        entry.isVideo -> "Video, ${fileSize(entry.sizeBytes)}. Tap, then Save to download and play" to false
        !entry.isJpeg -> "Preview only. RAW files are not downloaded" to false
        view?.stage == ViewStage.PreviewReady -> null
        view?.stage == ViewStage.Failed -> "Could not load the preview" to false
        canReach -> "Getting the preview" to true
        else -> null
    }
}

@Composable
private fun LoadingPill(label: Pair<String, Boolean>, modifier: Modifier) {
    val (text, spinning) = label
    Row(
        modifier.animateContentSize(tween(FADE_MS)).background(Color.Black.copy(alpha = 0.7f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (spinning) {
            CircularProgressIndicator(Modifier.size(12.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 1.5.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (spinning) Color.White else StatusColors.Attention, maxLines = 2)
    }
}

/** One action in a row along the bottom: the viewer's tools and the selection bars. */
@Composable
fun Tool(icon: ImageVector, label: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.size(width = 58.dp, height = 56.dp).alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Crossfade(busy, animationSpec = tween(FADE_MS), label = "tool") {
            if (it) {
                CircularProgressIndicator(Modifier.padding(2.dp).size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            } else {
                Icon(icon, label, Modifier.size(22.dp), tint = Color.White)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun ViewerPage(page: Page, view: CatalogView?, active: Boolean, arriving: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit, onControls: (Boolean) -> Unit, onReady: () -> Unit) {
    // The preview stays on top until the original has actually drawn beneath it, then fades away to reveal it.
    val phone = page.phone
    if (phone?.isVideo == true) {
        LaunchedEffect(Unit) { onReady() }
        VideoPage(phone, active, onControls)
        return
    }
    // Keyed on the copy too: deleting it from here must bring the preview back, not leave an empty page.
    // A file still being saved or compressed is never opened: the preview stays until it is final.
    val original = phone?.takeUnless { arriving }
    var sharp by remember(page.key, original?.uri) { mutableStateOf(false) }
    val previewAlpha by animateFloatAsState(if (sharp) 0f else 1f, tween(SHARPEN_MS), label = "sharpen")
    Box(Modifier.fillMaxSize()) {
        if (original != null) {
            ZoomablePhoto(original, active, onTap, onZoomed) {
                sharp = true
                onReady()
            }
        }
        if (previewAlpha > 0f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = previewAlpha }) {
                // A phone copy is shown by its own thumbnail: the image the grid drew, so nothing jumps or changes shape.
                // The camera's preview only stays when it was already on screen as the original arrived.
                if (original != null && view?.preview == null || page.handle == null) {
                    phone?.let { PhoneThumb(it.uri, it.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, placeholder = false) }
                } else {
                    // Touches go to the original once it is there.
                    CameraPreview(page, view, onTap.takeIf { original == null }, if (original == null) onReady else ({}))
                }
            }
        }
    }
}

/** The camera's preview if it has come, else the thumbnail, else a shimmer. */
@Composable
private fun CameraPreview(page: Page, view: CatalogView?, onTap: (() -> Unit)?, onReady: () -> Unit) {
    val bytes = view?.preview ?: page.entry?.thumbnail
    val degrees = page.entry?.rotationDegrees ?: 0
    val shape = page.entry?.shapeRatio
    val bitmap by produceState<ShownPhoto?>(null, bytes, degrees, shape) {
        value = withContext(Dispatchers.Default) { bytes?.let { decodeUpright(it, degrees, shape) } } ?: value
    }
    LaunchedEffect(bitmap != null) { if (bitmap != null) onReady() }
    // The sharper image fades in over the one on screen, which stays solid, so the photo never dips to black.
    var base by remember { mutableStateOf<ShownPhoto?>(null) }
    val reveal = remember(bitmap) { Animatable(0f) }
    LaunchedEffect(bitmap) {
        val next = bitmap ?: return@LaunchedEffect
        reveal.animateTo(1f, tween(250))
        base = next
    }
    Box(Modifier.fillMaxSize().then(if (onTap != null) Modifier.clickable(interactionSource = null, indication = null, onClick = onTap) else Modifier), contentAlignment = Alignment.Center) {
        val under = base
        if (under != null) {
            Image(under.painter, page.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Shimmer(Modifier.fillMaxSize())
        }
        bitmap?.takeIf { it !== under }?.let { top ->
            Image(top.painter, page.name, Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value }, contentScale = ContentScale.Fit)
        }
    }
}

/**
 * Telephoto does the gestures: zoom around the fingers, fling, rubber-banded edges and a hand-off to the pager at the
 * image's edge. The original is decoded in tiles, so it stays sharp at full zoom without loading it all into memory.
 */
@Composable
private fun ZoomablePhoto(photo: SavedPhoto, active: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit, onReady: () -> Unit) {
    val zoom = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val image = rememberSubSamplingImageState(
        imageSource = SubSamplingImageSource.contentUri(Uri.parse(photo.uri)),
        zoomableState = zoom,
    )
    // A photo swiped away comes back at its normal size.
    LaunchedEffect(active) { if (!active) zoom.resetZoom() }
    // Derived, so a pinch does not recompose this page on every frame.
    val isZoomed by remember(zoom) { derivedStateOf { (zoom.zoomFraction ?: 0f) > 0.01f } }
    LaunchedEffect(isZoomed, active) { if (active) onZoomed(isZoomed) }
    // Fully drawn, not just its coarse first pass. The wait is for a tile that never decodes.
    LaunchedEffect(image.isImageDisplayed, image.isImageDisplayedInFullQuality) {
        if (!image.isImageDisplayed) return@LaunchedEffect
        if (!image.isImageDisplayedInFullQuality) delay(FULL_QUALITY_MAX_MS)
        onReady()
    }

    // Touch events come faster than frames, so a pinch collects here and is applied once per frame.
    val pinch = remember { PinchStep() }
    LaunchedEffect(zoom) {
        while (true) {
            withFrameNanos { }
            val factor = pinch.zoom
            val pan = pinch.pan
            pinch.zoom = 1f
            pinch.pan = Offset.Zero
            if (factor != 1f) zoom.zoomBy(factor, pinch.centroid, snap())
            if (pan != Offset.Zero) zoom.panBy(pan, snap())
        }
    }
    Box(
        Modifier.fillMaxSize().pointerInput(zoom) {
            // One pinch moves at most one step, however far the fingers travel.
            awaitEachGesture {
                var pinched = 1f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val pressed = event.changes.count { it.pressed }
                    if (pressed == 0) break
                    if (pressed < 2) continue
                    val allowed = (pinched * event.calculateZoom()).coerceIn(1f / ZOOM_STEP, ZOOM_STEP) / pinched
                    pinched *= allowed
                    pinch.zoom *= allowed
                    pinch.pan += event.calculatePan()
                    pinch.centroid = event.calculateCentroid()
                    event.changes.forEach { it.consume() }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        SubSamplingImage(
            state = image,
            contentDescription = photo.name,
            modifier = Modifier.fillMaxSize().zoomable(zoom, onClick = { onTap() }, onDoubleClick = StepZoom),
        )
    }
}

private class PinchStep {
    var zoom = 1f
    var pan = Offset.Zero
    var centroid = Offset.Unspecified
}

/** Double tap: one step closer each time, back to normal from the maximum. */
private val StepZoom = DoubleClickToZoomListener { state, centroid ->
    if ((state.zoomFraction ?: 0f) >= 0.99f) state.resetZoom() else state.zoomBy(ZOOM_STEP, centroid)
}

private class Exif(val camera: String?, val lens: String?, val exposure: String?, val taken: String?, val geotagged: Boolean, val resolution: String?)

/** From the image itself: EXIF sizes still describe the original after compression. */
private fun resolutionOf(context: Context, uri: Uri, orientation: Int): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sideways = orientation in 5..8
    val width = if (sideways) bounds.outHeight else bounds.outWidth
    val height = if (sideways) bounds.outWidth else bounds.outHeight
    return "$width x $height, %.1f MP".format(width * height / 1e6)
}

@Composable
private fun PhotoInfo(photo: SavedPhoto) {
    val context = LocalContext.current
    val exif by produceState<Exif?>(null, photo.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                // Without this the system strips the location from what we read, and "geotagged" would always be false.
                val uri = runCatching { android.provider.MediaStore.setRequireOriginal(Uri.parse(photo.uri)) }.getOrDefault(Uri.parse(photo.uri))
                val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?: context.contentResolver.openInputStream(Uri.parse(photo.uri))
                stream!!.use { input ->
                    val e = ExifInterface(input)
                    val iso = e.getAttribute("PhotographicSensitivity")?.let { "ISO $it" }
                    val aperture = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/%.1f".format(it) }
                    val shutter = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
                        ?.let { if (it < 1) "1/${Math.round(1 / it)} s" else "%.1f s".format(it) }
                    val focal = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "%.0f mm".format(it) }
                    Exif(
                        camera = e.getAttribute(ExifInterface.TAG_MODEL),
                        lens = e.getAttribute("LensModel"),
                        exposure = listOfNotNull(focal, aperture, shutter, iso).joinToString("  ").ifEmpty { null },
                        taken = e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                        geotagged = e.getLatLong(FloatArray(2)),
                        resolution = resolutionOf(context, Uri.parse(photo.uri), e.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)),
                    )
                }
            }.getOrNull()
        }
    }
    Surface(Modifier.fillMaxWidth(), color = Color.Transparent, contentColor = Color.White) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val info = exif
            info?.exposure?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            listOfNotNull(info?.camera, info?.lens).joinToString(", ").ifEmpty { null }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            info?.taken?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.padding(top = 2.dp))
            info?.resolution?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "${fileSize(photo.sizeBytes)}, ${if (info?.geotagged == true) "geotagged" else "no location"}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_ZOOM = 8f
private const val ZOOM_STEP = 2.5f
private const val SHARPEN_MS = 350
private const val DOWN_BIAS = 1.5f
private const val SETTLE_MS = 400L
private const val FADE_MS = 180
private const val LABEL_DELAY_MS = 500L
private const val TRAVEL_MS = 280
private const val HANDOVER_MAX_MS = 1_500L // the opening image holds until the original has drawn beneath it
private const val FULL_QUALITY_MAX_MS = 3_000L
private const val DRAG_FULL = 0.5f
private const val DRAG_SHRINK = 0.2f
private const val DISMISS_FRACTION = 0.15f
private const val DISMISS_VELOCITY = 1_500f
