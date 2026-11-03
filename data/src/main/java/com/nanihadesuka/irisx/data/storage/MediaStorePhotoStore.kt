package com.nanihadesuka.irisx.data.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.nanihadesuka.irisx.domain.model.CompressOutcome
import com.nanihadesuka.irisx.domain.model.CompressionPreview
import com.nanihadesuka.irisx.domain.model.SavedPhoto
import com.nanihadesuka.irisx.domain.model.Settings
import com.nanihadesuka.irisx.domain.model.VideoPreset
import com.nanihadesuka.irisx.camera.api.isVideoName
import com.nanihadesuka.irisx.domain.model.smallVideoName
import com.nanihadesuka.irisx.domain.repository.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Saves into the shared `Pictures/Iris X` folder so any gallery or backup app can see the files. */
class MediaStorePhotoStore(context: Context) : PhotoStore {
    private val resolver = context.applicationContext.contentResolver
    private val packageManager = context.applicationContext.packageManager
    private val packageName = context.applicationContext.packageName
    /** Names of compressed copies, so a smaller file is not mistaken for a reduced one and replaced by the original again. */
    private val compressedNames = context.applicationContext.getSharedPreferences("compressed_photos", Context.MODE_PRIVATE)
    private val compressing = Mutex()
    private val refreshes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val videos: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val appContext = context.applicationContext

    private fun collectionFor(name: String) = if (isVideoName(name)) videos else collection

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mov" -> "video/quicktime"
        "mp4" -> "video/mp4"
        else -> "image/jpeg"
    }

    override val recent: Flow<List<SavedPhoto>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        resolver.registerContentObserver(videos, true, observer)
        trySend(Unit)
        val refresh = launch { refreshes.collect { trySend(Unit) } }
        awaitClose {
            refresh.cancel()
            resolver.unregisterContentObserver(observer)
        }
    }.conflate().map { query(limit = MAX_LISTED) }.flowOn(Dispatchers.IO)

    override suspend fun save(name: String, write: suspend (OutputStream) -> Unit): SavedPhoto =
        withContext(Dispatchers.IO) {
            compressedNames.edit().remove(name).apply()
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(name))
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(collectionFor(name), values) ?: error("Could not create $name")
            try {
                resolver.openOutputStream(uri)!!.use { write(it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            val size = resolver.query(uri, arrayOf(MediaStore.Images.Media.SIZE), null, null, null)
                ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
            SavedPhoto(uri.toString(), name, now, size)
        }

    private fun copiesNamed(name: String): List<Pair<Uri, Long>> {
        val found = mutableListOf<Pair<Uri, Long>>()
        val from = collectionFor(name)
        resolver.query(
            from,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("$RELATIVE_PATH%", name),
            null,
        )?.use { c -> while (c.moveToNext()) found += ContentUris.withAppendedId(from, c.getLong(0)) to c.getLong(1) }
        return found
    }

    private fun isInBackup(uri: Uri): Boolean =
        resolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)
            ?.use { it.moveToFirst() && it.getString(0) == BACKUP_PATH } ?: false

    override suspend fun existingSize(name: String): Long? = withContext(Dispatchers.IO) {
        copiesNamed(name).maxOfOrNull { it.second }?.let { if (compressedNames.contains(name)) Long.MAX_VALUE else it }
    }

    override suspend fun smallCopy(name: String, longEdge: Int): ByteArray? = withContext(Dispatchers.IO) {
        val uri = copiesNamed(name).firstOrNull()?.first ?: return@withContext null
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) } ?: return@runCatching null
            val scale = longEdge.toFloat() / maxOf(decoded.width, decoded.height)
            val fitted = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
            java.io.ByteArrayOutputStream().also { fitted.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }.getOrNull()
    }

    override suspend fun longestEdge(name: String): Int? = withContext(Dispatchers.IO) {
        val uri = copiesNamed(name).firstOrNull()?.first ?: return@withContext null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
        maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 }
    }

    override suspend fun saveReplacing(name: String, write: suspend (OutputStream) -> Unit): SavedPhoto =
        withContext(Dispatchers.IO) {
            compressedNames.edit().remove(name).apply()
            val old = copiesNamed(name)
            val wasQueued = old.any { (uri, _) -> isInBackup(uri) }
            // Written under a temporary name, so a failed transfer leaves the existing copy untouched.
            val saved = save("$name.part.${name.substringAfterLast('.', "jpg")}", write)
            old.forEach { (uri, _) -> runCatching { resolver.delete(uri, null, null) } }
            resolver.update(Uri.parse(saved.uri), ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, name) }, null, null)
            // A better copy of a photo queued for backup stays queued.
            if (wasQueued) moveToBackup(saved.uri)
            saved.copy(name = name, queuedForBackup = wasQueued)
        }

    // One at a time, so a photo queued after download and sent to backup at the same moment is not compressed twice.
    override suspend fun compress(uri: String, settings: Settings): CompressOutcome = compressing.withLock { compressOnce(uri, settings) }

    private suspend fun compressOnce(uri: String, settings: Settings): CompressOutcome = withContext(Dispatchers.Default) {
        val target = Uri.parse(uri)
        val name = resolver.query(target, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return@withContext CompressOutcome.Failed
        // Every route ends here, so this is the one check that a photo is never compressed a second time.
        if (compressedNames.contains(name) || isVideoName(name)) return@withContext CompressOutcome.Unchanged
        // The original form or nothing: a redacted read would drop the location from the new file for good.
        val original = withContext(Dispatchers.IO) {
            resolver.openInputStream(MediaStore.setRequireOriginal(target))!!.use { it.readBytes() }
        }
        val smaller = JpegCompressor.compress(original, settings) ?: return@withContext CompressOutcome.Unchanged
        withContext(Dispatchers.IO) {
            // Rewritten in place, keeping the photo's uri and position. A failed write puts the original back.
            val out = resolver.openOutputStream(target, "wt") ?: error("Could not open $name")
            try {
                out.use { it.write(smaller) }
            } catch (e: java.io.IOException) {
                resolver.openOutputStream(target, "wt")?.use { it.write(original) }
                throw e
            }
            compressedNames.edit().putBoolean(name, true).apply()
        }
        CompressOutcome.Compressed((original.size - smaller.size).toLong())
    }

    override suspend fun compressVideo(uri: String, name: String, preset: VideoPreset, onProgress: (Int) -> Unit): String {
        val source = Uri.parse(uri)
        val output = java.io.File.createTempFile("video", ".mp4", appContext.cacheDir)
        try {
            transcodeVideo(appContext, source, output, preset, onProgress)
            return withContext(Dispatchers.IO) {
                val originalSize = resolver.query(source, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
                check(output.length() in 1 until originalSize) { "This video is already small: the result would not save space" }
                val taken = resolver.query(source, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
                val smallName = smallVideoName(name)
                val saved = save(smallName) { out -> output.inputStream().use { it.copyTo(out) } }
                if (taken > 0) resolver.update(Uri.parse(saved.uri), ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, taken) }, null, null)
                compressedNames.edit().putBoolean(smallName, true).apply()
                saved.uri
            }
        } finally {
            output.delete()
        }
    }

    override suspend fun uncompressedUri(name: String): String? = withContext(Dispatchers.IO) {
        if (compressedNames.contains(name)) null else copiesNamed(name).firstOrNull()?.first?.toString()
    }

    override suspend fun previewCompression(uri: String, settings: Settings): CompressionPreview = withContext(Dispatchers.Default) {
        val original = withContext(Dispatchers.IO) {
            resolver.openInputStream(MediaStore.setRequireOriginal(Uri.parse(uri)))!!.use { it.readBytes() }
        }
        CompressionPreview(original, JpegCompressor.compress(original, settings))
    }

    override suspend fun moveToBackup(uri: String): Unit = withContext(Dispatchers.IO) {
        resolver.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.Images.Media.RELATIVE_PATH, BACKUP_PATH) }, null, null)
        Unit
    }

    override fun folderPredatesInstall(): Boolean {
        val folder = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Iris X")
        val installed = runCatching { packageManager.getPackageInfo(packageName, 0).firstInstallTime }.getOrDefault(0L)
        return folder.exists() && folder.lastModified() in 1 until installed
    }

    override fun refresh() {
        refreshes.tryEmit(Unit)
    }

    private fun query(limit: Int): List<SavedPhoto> =
        (read(collection, video = false) + read(videos, video = true)).sortedByDescending { it.savedAtMillis }.take(limit)

    private fun read(from: Uri, video: Boolean): List<SavedPhoto> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
        )
        val result = mutableListOf<SavedPhoto>()
        resolver.query(
            from,
            projection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("$RELATIVE_PATH%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                result += SavedPhoto(
                    uri = ContentUris.withAppendedId(from, c.getLong(0)).toString(),
                    name = c.getString(1),
                    savedAtMillis = c.getLong(2) * 1000,
                    sizeBytes = c.getLong(3),
                    takenAtMillis = c.getLong(4),
                    queuedForBackup = c.getString(5) == BACKUP_PATH,
                    compressed = compressedNames.contains(c.getString(1)),
                    durationMillis = if (video) c.getLong(6) else 0,
                )
            }
        }
        return result
    }

    private companion object {
        const val RELATIVE_PATH = "Pictures/Iris X/"
        /** Inside the main folder, so every listing still finds it. Google Photos sees it as its own device folder. */
        const val BACKUP_PATH = "Pictures/Iris X/Iris X Backup/"
        const val MAX_LISTED = 5_000
    }
}
