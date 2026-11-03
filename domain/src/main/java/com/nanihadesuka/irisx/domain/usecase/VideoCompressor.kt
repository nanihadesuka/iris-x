package com.nanihadesuka.irisx.domain.usecase

import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.VideoJob
import com.nanihadesuka.irisx.domain.model.VideoPreset
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One video at a time, only when asked. The original is never changed: the smaller copy is a new file beside it. */
class VideoCompressor(private val store: PhotoStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow<VideoJob?>(null)
    val state: StateFlow<VideoJob?> = _state

    /** With [thenBackup] the smaller copy goes to the backup folder; the original never does. */
    @Synchronized
    fun start(video: SavedPhoto, preset: VideoPreset, thenBackup: Boolean) {
        if (job?.isActive == true) return
        _state.value = VideoJob(video.name, thenBackup)
        job = scope.launch {
            try {
                val small = store.compressVideo(video.uri, video.name, preset) { percent ->
                    _state.value = _state.value?.copy(percent = percent)
                }
                if (thenBackup) store.moveToBackup(small)
                _state.value = _state.value?.copy(percent = 100, running = false)
            } catch (e: CancellationException) {
                _state.value = null
                throw e
            } catch (e: Exception) {
                _state.value = _state.value?.copy(running = false, error = e.message ?: "Could not compress the video")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun clear() {
        if (job?.isActive != true) _state.value = null
    }
}
