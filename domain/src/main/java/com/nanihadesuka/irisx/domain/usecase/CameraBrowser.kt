package com.nanihadesuka.irisx.domain.usecase

import com.nanihadesuka.irisx.domain.model.CatalogDownload
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.domain.model.CatalogPhase
import com.nanihadesuka.irisx.domain.model.CatalogState
import com.nanihadesuka.irisx.camera.api.SessionPurpose
import com.nanihadesuka.irisx.domain.model.CatalogView
import com.nanihadesuka.irisx.domain.model.Attention
import com.nanihadesuka.irisx.camera.api.CardChange
import com.nanihadesuka.irisx.domain.model.ConnectionState
import com.nanihadesuka.irisx.domain.model.FULL_SIZE_MIN_EDGE
import com.nanihadesuka.irisx.camera.api.changeSince
import com.nanihadesuka.irisx.domain.model.TransferState
import com.nanihadesuka.irisx.domain.model.ViewStage
import com.nanihadesuka.irisx.domain.repository.CameraConnection
import com.nanihadesuka.irisx.domain.repository.CameraRepository
import com.nanihadesuka.irisx.domain.repository.CatalogCache
import com.nanihadesuka.irisx.camera.api.PhotoSession
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.camera.api.ReadCancelled
import com.nanihadesuka.irisx.domain.repository.PhotoTransferGateway
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import com.nanihadesuka.irisx.domain.repository.SyncActivity
import com.nanihadesuka.irisx.domain.repository.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

/**
 * Browsing photos: BLE FunctionLaunchRequest (InCameraViewIng) -> camera AP launched -> Wi-Fi -> PTP session in
 * photo-view function mode. One session reads every thumbnail newest first and serves downloads, then closes when idle
 * so the phone gets its normal network back. Lives outside any screen, so switching tabs does not interrupt it.
 */
class CameraBrowser(
    private val cameras: CameraRepository,
    private val connection: CameraConnection,
    private val gateway: PhotoTransferGateway,
    private val store: PhotoStore,
    private val activity: SyncActivity,
    private val cache: CatalogCache,
    private val compressor: PhotoCompressor,
    private val settings: SettingsRepository,
    private val controller: SyncController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloads = Channel<List<CatalogEntry>>(Channel.UNLIMITED)
    private val views = Channel<CatalogEntry>(Channel.UNLIMITED)
    private val upgrades = mutableSetOf<String>()
    private var job: Job? = null
    @Volatile private var visible: List<Int> = emptyList()
    private val visibleChanged = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var browsed = false
    @Volatile private var fetchingNew = false
    @Volatile private var failuresInARow = 0
    @Volatile private var activeSession: PhotoSession? = null
    /** The photo whose preview is being read. Only ever a preview: a download is never cancelled by viewing. */
    private var readingFor: Int? = null
    private val readingLock = Any()
    /** Handles whose thumbnail was tried and did not arrive; asked again in the next app run. */
    private val noThumbnail = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state

    private val saveSlots = Semaphore(MAX_PENDING_SAVES)

    private val loadLock = Mutex()
    private var loadedFor: String? = null

    init {
        scope.launch { cameras.current()?.let { loadCache(it.bleAddress) } }
    }

    /** Shows what the last visit read, before and without any connection. */
    private suspend fun loadCache(address: String) = loadLock.withLock {
        if (loadedFor == address) return@withLock
        loadedFor = address
        val cached = cache.load(address).sortedByDescending { it.handle }
        if (cached.isNotEmpty()) _state.update { it.copy(entries = cached, handles = cached.map { e -> e.handle }, total = cached.size) }
    }

    /** Opens a session unless one is already running. Thumbnails already read are kept. */
    @Synchronized
    fun open() {
        browsed = true
        if (job?.isActive == true) return
        job = scope.launch { session() }
    }

    /** Queues copies to the phone, opening a session if needed. Never touches the camera's files. */
    fun download(entries: List<CatalogEntry>) {
        val wanted = entries.filter { it.isJpeg || it.isVideo }
        if (wanted.isEmpty()) return
        // Decided on the phone, before asking the camera for anything: no work means no Wi-Fi.
        scope.launch {
            val needed = wanted.filter { needsDownload(it) }
            if (needed.isEmpty()) {
                _state.update { it.copy(message = "Already on the phone") }
            } else {
                val skipped = wanted.size - needed.size
                if (skipped > 0) _state.update { it.copy(message = "Getting ${needed.size}, $skipped already on the phone") }
                downloads.trySend(needed)
                open()
            }
        }
    }

    /** Not here, or a reduced copy. A compressed copy reports the largest size, so it is never replaced. */
    private suspend fun needsDownload(entry: CatalogEntry): Boolean {
        val have = store.existingSize(entry.name) ?: return true
        return have != Long.MAX_VALUE && (store.longestEdge(entry.name) ?: 0) < FULL_SIZE_MIN_EDGE
    }

    /**
     * The one Retry behind every button: gets the Bluetooth link back if needed, then browses. Two failures in a row
     * with the link up restart the link too, because the camera may have lost track of the phone.
     */
    @Synchronized
    fun recover() {
        browsed = true
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (linkReady()) session()
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = CatalogPhase.Idle) }
                activity.set(TransferState.Idle)
                throw e
            }
        }
    }

    private suspend fun linkReady(): Boolean {
        if (settings.settings.first().syncPaused) settings.update { it.copy(syncPaused = false) }
        controller.start()
        if (failuresInARow >= RESTART_LINK_AFTER && connection.state.value == ConnectionState.Connected) {
            connection.disconnect()
            failuresInARow = 0
            delay(LINK_RESTART_SETTLE_MS)
        }
        if (connection.state.value == ConnectionState.Connected) return true
        connection.reconnectNow()
        _state.update { it.copy(phase = CatalogPhase.Connecting, message = null) }
        activity.set(TransferState.WaitingForLink)
        val state = withTimeoutOrNull(LINK_TIMEOUT_MS) {
            connection.state.first { it == ConnectionState.Connected || it is ConnectionState.NeedsAttention }
        }
        if (state == ConnectionState.Connected) return true
        fail(
            when ((state as? ConnectionState.NeedsAttention)?.reason) {
                Attention.BluetoothOff -> "Bluetooth is off. Turn it on, then retry."
                Attention.PermissionMissing -> "Iris X needs permission to reach the camera."
                Attention.PairingLost -> "The pairing was lost. Pair the camera again."
                else -> "Camera not found. Check it is on and close by, then retry."
            },
        )
        return false
    }

    /**
     * Phone copies are about to go. The Camera tab shows what the camera holds, so it keeps a preview and thumbnail made
     * from them, and these photos can still be seen with no Wi-Fi. Harmless if the user then cancels the delete.
     */
    fun keepPreviews(names: List<String>) {
        scope.launch {
            val address = cameras.current()?.bleAddress ?: return@launch
            for (name in names) {
                val entry = _state.value.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: continue
                if (!entry.isJpeg) continue
                if (cache.loadPreview(address, entry.name) == null) {
                    store.smallCopy(entry.name, KEPT_PREVIEW_EDGE)?.let { cache.savePreview(address, entry.name, it) }
                }
                if (entry.thumbnail == null) {
                    val thumbnail = store.smallCopy(entry.name, KEPT_THUMBNAIL_EDGE) ?: continue
                    val kept = CatalogEntry(entry.handle, entry.name, entry.sizeBytes, thumbnail, entry.orientation, entry.capturedAt, entry.pixelWidth, entry.pixelHeight)
                    cache.put(address, kept)
                    _state.update { s -> s.copy(entries = s.entries.map { if (it.handle == entry.handle) kept else it }) }
                }
            }
        }
    }

    /** Browses, then saves the photos the listing shows are new. A card never seen before only lists: that is the user's call. */
    fun fetchNew() {
        fetchingNew = true
        recover()
    }

    /** The user pressed Stop. Queued work is dropped; files already saved stay. */
    fun stop() {
        if (job?.isActive != true) return
        activity.set(TransferState.Stopping)
        while (downloads.tryReceive().isSuccess) Unit
        while (views.tryReceive().isSuccess) Unit
        synchronized(upgrades) { upgrades.clear() }
        activeSession?.cancelRead()
        job?.cancel()
    }

    /** The viewer closed: previews still being read or waiting are no longer wanted. */
    fun stopViewing() = viewingOnly(null)

    /** The viewer stopped on [handle]: a preview of any other photo is dropped, so the camera is free for this one. */
    fun viewingOnly(handle: Int?) {
        _state.update { s -> if (s.viewing?.handle == handle) s else s.copy(viewing = null) }
        // Locked so a read that has just ended cannot have this land on the download after it.
        synchronized(readingLock) { if (readingFor != null && readingFor != handle) activeSession?.cancelRead() }
    }

    /** The photos on screen right now: their thumbnails are read next. */
    fun prioritize(handles: List<Int>) {
        val previous = visible
        visible = handles
        visibleChanged.trySend(Unit)
        // Scrolling onto unread photos after the idle close reconnects; just showing the tab does not.
        val state = _state.value
        val scrolled = previous.isNotEmpty() && previous != handles
        if (browsed && scrolled && job?.isActive != true && state.incoming == null && state.phase != CatalogPhase.Failed &&
            connection.state.value == ConnectionState.Connected
        ) scope.launch { if (nextThumbnail(state.handles) != null) open() }
    }

    /** The camera's preview, from the disk cache when it can be. The original only comes with [download]. */
    fun view(entry: CatalogEntry, network: Boolean = true) {
        viewingOnly(entry.handle)
        _state.update { it.copy(viewing = CatalogView(entry.handle, ViewStage.Waiting)) }
        scope.launch {
            val cached = cameras.current()?.let { cache.loadPreview(it.bleAddress, entry.name) }
            if (cached != null) {
                _state.update { s -> if (s.viewing?.handle == entry.handle) s.copy(viewing = CatalogView(entry.handle, ViewStage.PreviewReady, cached)) else s }
            } else if (network) {
                views.trySend(entry)
                open()
            } else {
                // Nothing kept and no camera: the thumbnail stays, and the viewer says so.
                _state.update { s -> if (s.viewing?.handle == entry.handle) s.copy(viewing = null) else s }
            }
        }
    }

    /** The camera has photos for us: show it now, and clear the way for the transfer session. */
    suspend fun incomingStarted() {
        job?.cancelAndJoin()
        _state.update { it.copy(incoming = 0) }
    }

    fun incomingCount(left: Int) {
        _state.update { it.copy(incoming = left.coerceAtLeast(0)) }
    }

    fun incomingCleared() {
        _state.update { it.copy(incoming = null) }
    }

    /** The transfer is done: refresh the gallery, then fetch originals of [names] where only a reduced copy arrived. */
    fun incomingFinished(names: List<String>) {
        synchronized(upgrades) { upgrades += names }
        incomingCleared()
        // The grid catches up at the next browse: Wi-Fi only comes back when an original is still needed.
        if (names.isNotEmpty()) open()
    }

    fun close() {
        job?.cancel()
    }

    /** The camera is being unpaired: its thumbnails go with it. */
    suspend fun forget(address: String) {
        job?.cancelAndJoin()
        cache.clear(address)
        loadLock.withLock { loadedFor = null }
        _state.value = CatalogState()
    }

    private suspend fun session() {
        val camera = cameras.current() ?: return
        loadCache(camera.bleAddress)
        // Checked first, so the camera is not woken for a session that cannot happen. Says nothing about the link.
        if (!gateway.wifiEnabled) {
            fail("Wi-Fi is off. Turn it on, then retry.")
            failuresInARow = 0
            return
        }
        _state.update { it.copy(phase = CatalogPhase.Connecting, message = null) }
        activity.set(TransferState.WaitingForCamera)
        try {
            connection.requestAccessPoint()
            withTimeout(AP_TIMEOUT_MS) { connection.accessPointLaunched.first() }
            activity.set(TransferState.JoiningWifi())
            gateway.withSession(camera, SessionPurpose.PhotoCatalog) { serve(it, camera.bleAddress) }
            _state.update { it.copy(phase = CatalogPhase.Idle) }
            activity.set(TransferState.Idle)
        } catch (e: TimeoutCancellationException) {
            fail("The camera did not start its Wi-Fi. Wake it and try again.")
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = CatalogPhase.Idle, download = null) }
            activity.set(TransferState.Idle)
            throw e
        } catch (e: Exception) {
            fail(e.message ?: "Could not reach the camera")
        }
    }

    private fun fail(message: String) {
        failuresInARow++
        _state.update { s ->
            s.copy(
                phase = CatalogPhase.Failed,
                download = null,
                message = message,
                viewing = s.viewing?.let { CatalogView(it.handle, ViewStage.Failed, it.preview) },
            )
        }
        activity.set(TransferState.Failed(message))
    }

    private suspend fun serve(session: PhotoSession, address: String) {
        activeSession = session
        failuresInARow = 0
        try {
            serveSession(session, address)
        } finally {
            activeSession = null
        }
    }

    private suspend fun serveSession(session: PhotoSession, address: String) {
        val listing = session.catalogListing()
        val alreadyKnown = _state.value.entries.mapTo(HashSet()) { it.handle }
        val handles = listing.handles
        val change = listing.changeSince(cache.loadFingerprint(address))
        // A swapped or formatted card reuses handles for different photos. The listing rules that out when only newer
        // photos were added, or nothing changed; otherwise two reads check the cache against the card.
        if (change != CardChange.Unchanged && change != CardChange.Added && !cacheMatchesCard(session, handles)) {
            cache.clear(address)
            _state.update { it.copy(entries = emptyList()) }
        }
        // An empty list may just be a card problem; only a real listing may shrink the cache.
        if (handles.isNotEmpty()) {
            cache.retain(address, handles.toSet())
            cache.saveFingerprint(address, listing)
        }
        val added = handles.size - _state.value.entries.count { it.handle in handles }
        when {
            change == CardChange.Unchanged && added == 0 -> _state.update { it.copy(message = "Nothing new on the card") }
            change == CardChange.Added && added > 0 -> _state.update { it.copy(message = "$added new on the card") }
        }
        _state.update { s -> s.copy(phase = CatalogPhase.Reading, total = handles.size, handles = handles, entries = s.entries.filter { it.handle in handles }) }
        activity.set(TransferState.Browsing)
        // Names and types of the whole card are read once, because downloads and filters need them. Thumbnails are the
        // heavy part, so only those on screen are read, and the session can close sooner.
        // Cached entries with no orientation yet are read again; they stay on screen until replaced.
        val pendingInfo = (handles - _state.value.entries.filter { it.orientation != 0 && it.capturedAt.isNotEmpty() && it.pixelWidth != 0 }.map { it.handle }.toSet()).toMutableSet()
        var failures = 0
        while (true) {
            // Downloads first: looking at photos must never hold them back.
            takeUpgrades().takeIf { it.isNotEmpty() }?.let { run(session, it); continue }
            downloads.tryReceive().getOrNull()?.let { run(session, it); continue }
            views.tryReceive().getOrNull()?.let { show(session, it); continue }
            val thumbnail = nextThumbnail(handles)
            if (thumbnail == null && pendingInfo.isNotEmpty()) activity.set(TransferState.ReadingCard(handles.size - pendingInfo.size, handles.size))
            val handle = thumbnail ?: handles.firstOrNull { it in pendingInfo }
            if (handle != null) {
                pendingInfo -= handle
                val entry = session.catalogEntry(handle, withThumbnail = thumbnail != null)
                failures = if (entry == null) failures + 1 else 0
                if (thumbnail != null && entry?.thumbnail == null) noThumbnail += handle
                check(failures < MAX_READ_FAILURES) { "The camera stopped answering" }
                if (entry != null) {
                    val known = _state.value.entries.firstOrNull { it.handle == handle && it.name == entry.name }
                    val merged = CatalogEntry(handle, entry.name, entry.sizeBytes, entry.thumbnail ?: known?.thumbnail, entry.orientation, entry.capturedAt, entry.pixelWidth, entry.pixelHeight)
                    cache.put(address, merged)
                    _state.update { s -> s.copy(entries = (s.entries.filter { it.handle != handle } + merged).sortedByDescending { it.handle }) }
                }
                continue
            }
            if (fetchingNew) {
                fetchingNew = false
                val fresh = if (alreadyKnown.isEmpty()) emptyList() else _state.value.entries.filter { it.handle !in alreadyKnown && it.isJpeg && needsDownload(it) }
                if (fresh.isNotEmpty()) run(session, fresh)
                settings.update { it.copy(photosWaiting = false) }
                continue
            }
            // Whatever was waiting can now be seen in the grid.
            if (settings.settings.first().photosWaiting) settings.update { it.copy(photosWaiting = false) }
            _state.update { it.copy(phase = CatalogPhase.Live) }
            activity.set(TransferState.Browsing)
            // With a card known to be unchanged there is little left to ask, so the camera is let go sooner.
            val idle = if (change == CardChange.Unchanged && added == 0) UNCHANGED_IDLE_CLOSE_MS else IDLE_CLOSE_MS
            val next = withTimeoutOrNull(idle) {
                select<suspend () -> Unit> {
                    views.onReceive { entry -> suspend { show(session, entry) } }
                    downloads.onReceive { entries -> suspend { run(session, entries) } }
                    visibleChanged.onReceive { suspend {} }
                }
            } ?: return
            next()
        }
    }

    /** Originals still wanted whose thumbnail has been read, so their handle is known. */
    private fun takeUpgrades(): List<CatalogEntry> = synchronized(upgrades) {
        _state.value.entries.filter { it.name in upgrades }.also { found -> upgrades -= found.map { it.name }.toSet() }
    }

    /** The next photo on screen still without a thumbnail. A photo on the phone shows the phone copy, so it needs none. */
    private suspend fun nextThumbnail(handles: List<Int>): Int? {
        val entries = _state.value.entries.associateBy { it.handle }
        return visible.firstOrNull { handle ->
            val entry = entries[handle]
            handle in handles && entry?.thumbnail == null && handle !in noThumbnail &&
                (entry == null || store.existingSize(entry.name) == null)
        }
    }

    /**
     * Compares the newest and oldest cached photos still listed: two reads instead of re-reading the whole card.
     * A failed read proves nothing, so it keeps the cache; downloads check the name again before saving.
     */
    private suspend fun cacheMatchesCard(session: PhotoSession, handles: List<Int>): Boolean {
        val listed = _state.value.entries.filter { it.handle in handles }
        return listOfNotNull(listed.firstOrNull(), listed.lastOrNull()).distinct().all { cached ->
            session.photoInfo(cached.handle)?.let { it.name == cached.name } ?: true
        }
    }

    private suspend fun show(session: PhotoSession, entry: CatalogEntry) {
        // Swiped past before its turn came: only the photo being looked at is fetched.
        if (_state.value.viewing?.handle != entry.handle) return
        fun stage(stage: ViewStage, preview: ByteArray?) = _state.update { s ->
            val current = s.viewing
            if (current?.handle != entry.handle) s
            else s.copy(viewing = CatalogView(entry.handle, stage, preview ?: current.preview))
        }
        synchronized(readingLock) { readingFor = entry.handle }
        try {
            showReading(session, entry, ::stage)
        } catch (e: ReadCancelled) {
            _state.update { it.copy(download = null) }
            activity.set(TransferState.Browsing)
            session.requestOriginals(false)
        } finally {
            synchronized(readingLock) { readingFor = null }
        }
    }

    private suspend fun showReading(session: PhotoSession, entry: CatalogEntry, stage: (ViewStage, ByteArray?) -> Unit) {
        stage(ViewStage.Preview, null)
        // With compression off the camera returns its small preview; a failure here must not stop the original.
        // A photo already on the phone has no use for it.
        val photo = if (store.existingSize(entry.name) == null) session.photoInfo(entry.handle) else null
        // Swiped away during that question: do not start the read at all.
        if (_state.value.viewing?.handle != entry.handle) return
        if (photo != null && photo.name == entry.name) {
            val bytes = ByteArrayOutputStream()
            try {
                session.download(photo, bytes)
                stage(ViewStage.PreviewReady, bytes.toByteArray())
                cameras.current()?.let { cache.savePreview(it.bleAddress, entry.name, bytes.toByteArray()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ReadCancelled) {
                throw e
            } catch (e: Exception) {
                stage(ViewStage.Failed, null)
            }
        } else if (photo == null && store.existingSize(entry.name) == null) {
            stage(ViewStage.Failed, null)
        }
    }

    private suspend fun run(session: PhotoSession, entries: List<CatalogEntry>): Int {
        var saved = 0
        val saves = mutableListOf<Job>()
        val saveFailures = java.util.concurrent.atomic.AtomicInteger()
        session.requestOriginals(true)
        entries.forEachIndexed { index, entry ->
            _state.update { it.copy(download = CatalogDownload(index, entries.size, entry.name)) }
            activity.set(TransferState.Downloading(index, entries.size, entry.name))
            val photo = session.photoInfo(entry.handle)
            // Handles can shift if the card changed since the thumbnail was read; never save the wrong file.
            if (photo != null && photo.name == entry.name) {
                // Larger than the phone copy means the phone has a reduced version; equal or smaller, nothing to gain.
                val have = store.existingSize(photo.name)
                val progress = { bytes: Long ->
                    _state.update { s -> s.copy(download = s.download?.copy(currentBytes = bytes, currentSize = photo.sizeBytes)) }
                    activity.set(TransferState.Downloading(index, entries.size, entry.name, bytes, photo.sizeBytes))
                }
                saved++
                when {
                    have != null && have >= photo.sizeBytes -> compressor.afterDownload(photo.name)
                    // A video is streamed straight to its file, because it may not fit in memory.
                    entry.isVideo -> {
                        store.saveReplacing(photo.name) { out -> session.download(photo, out, progress) }
                        compressor.afterDownload(photo.name)
                    }
                    else -> {
                        // Saved beside the transfer, so the camera is already sending the next photo. The wait for a slot
                        // comes first, so the received photos held in memory stay few.
                        saveSlots.acquire()
                        var handedOver = false
                        try {
                            val received = ByteArrayOutputStream(photo.sizeBytes.toInt())
                            session.download(photo, received, progress)
                            compressor.arriving(photo.name)
                            handedOver = true
                            // In the browser's own scope: a photo already received is kept even if the session then ends.
                            saves += scope.launch {
                                try {
                                    store.saveReplacing(photo.name) { out -> received.writeTo(out) }
                                    compressor.afterDownload(photo.name)
                                } catch (e: Exception) {
                                    compressor.abandoned(photo.name)
                                    saveFailures.incrementAndGet()
                                } finally {
                                    saveSlots.release()
                                }
                            }
                        } finally {
                            if (!handedOver) saveSlots.release()
                        }
                    }
                }
            }
        }
        session.requestOriginals(false) // thumbnails may still be pending
        saves.joinAll()
        saved -= saveFailures.get()
        val skipped = entries.size - saved
        _state.update {
            it.copy(
                download = null,
                message = if (skipped == 0) "$saved saved to Pictures/Iris X" else "$saved saved, $skipped failed",
            )
        }
        activity.set(TransferState.Browsing)
        return saved
    }

    private companion object {
        const val AP_TIMEOUT_MS = 30_000L
        const val IDLE_CLOSE_MS = 45_000L
        const val KEPT_PREVIEW_EDGE = 2688
        const val KEPT_THUMBNAIL_EDGE = 640
        const val RESTART_LINK_AFTER = 2
        const val LINK_RESTART_SETTLE_MS = 500L
        const val LINK_TIMEOUT_MS = 45_000L
        const val UNCHANGED_IDLE_CLOSE_MS = 15_000L
        const val MAX_READ_FAILURES = 3
        const val MAX_PENDING_SAVES = 2
    }
}
