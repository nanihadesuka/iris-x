package com.nanihadesuka.irisx.data.storage

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.nanihadesuka.irisx.domain.model.VideoPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Media3 Transformer with the phone's hardware encoder. It must be driven from the main thread. */
@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun transcodeVideo(context: Context, source: Uri, output: File, preset: VideoPreset, onProgress: (Int) -> Unit) =
    withContext(Dispatchers.Main) {
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(preset.bitrate).build())
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoders)
            .build()
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            // The short side, so a portrait clip gets the same quality as a landscape one.
            .setEffects(Effects(emptyList(), listOf(Presentation.createForShortSide(preset.shortSide))))
            .build()
        coroutineScope {
            val progress = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    delay(PROGRESS_POLL_MS)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            continuation.resumeWithException(exportException)
                        }
                    })
                    transformer.start(item, output.absolutePath)
                    continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { transformer.cancel() } }
                }
            } finally {
                progress.cancel()
            }
        }
    }

private const val PROGRESS_POLL_MS = 400L
