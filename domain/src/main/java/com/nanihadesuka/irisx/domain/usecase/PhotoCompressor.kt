package com.nanihadesuka.irisx.domain.usecase

import com.nanihadesuka.irisx.domain.model.CompressOutcome
import com.nanihadesuka.irisx.domain.model.CompressionProgress
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.camera.api.isVideoName
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import com.nanihadesuka.irisx.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Works through phone copies one at a time, outside any screen. Only ever touches files on the phone. */
class PhotoCompressor(private val store: PhotoStore, private val settings: SettingsRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Queued>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(CompressionProgress())
    val state: StateFlow<CompressionProgress> = _state

    /** Photos just downloaded whose file is still being rewritten, so they must not be opened yet. */
    private val _finishing = MutableStateFlow(emptySet<String>())
    val finishing: StateFlow<Set<String>> = _finishing

    init {
        scope.launch {
            for ((uri, custom, arrived) in queue) {
                val outcome = runCatching { store.compress(uri, custom ?: settings.settings.first()) }.getOrElse { CompressOutcome.Failed }
                if (arrived != null) _finishing.update { it - arrived }
                _state.update {
                    it.copy(
                        done = it.done + 1,
                        savedBytes = it.savedBytes + ((outcome as? CompressOutcome.Compressed)?.savedBytes ?: 0),
                        skipped = it.skipped + if (outcome == CompressOutcome.Unchanged) 1 else 0,
                        failed = it.failed + if (outcome == CompressOutcome.Failed) 1 else 0,
                    )
                }
            }
        }
    }

    /** A photo has been received and is being saved: not to be opened until [afterDownload]. */
    fun arriving(name: String) = _finishing.update { it + name }

    /** Its save failed, so nothing more is coming. */
    fun abandoned(name: String) = _finishing.update { it - name }

    /** A photo has just reached the phone, as its final copy. */
    fun afterDownload(name: String) {
        _finishing.update { it + name }
        scope.launch {
            // Videos are only ever compressed when asked.
            val uri = if (!isVideoName(name) && settings.settings.first().compressOnDownload) runCatching { store.uncompressedUri(name) }.getOrNull() else null
            if (uri != null) enqueue(listOf(uri), arrived = name) else _finishing.update { it - name }
        }
    }

    /** Waits, so a photo is at its final size before Google Photos can see it. A failure leaves it as it was. */
    suspend fun beforeBackup(uri: String) {
        val current = settings.settings.first()
        if (current.compressOnBackup) runCatching { store.compress(uri, current) }
    }

    /** [custom] holds settings chosen for particular photos; the rest use the saved ones. */
    fun enqueue(uris: List<String>, custom: Map<String, Settings> = emptyMap(), arrived: String? = null) {
        if (uris.isEmpty()) return
        // A finished batch's totals are replaced; one still running just grows.
        _state.update { if (it.running) it.copy(total = it.total + uris.size) else CompressionProgress(total = uris.size) }
        uris.forEach { queue.trySend(Queued(it, custom[it], arrived)) }
    }

    private data class Queued(val uri: String, val custom: Settings?, val arrived: String?)
}
