package com.nanihadesuka.irisx.data.storage

import android.content.Context
import com.nanihadesuka.irisx.camera.api.CardFingerprint
import com.nanihadesuka.irisx.camera.api.CatalogEntry
import com.nanihadesuka.irisx.domain.repository.CatalogCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** One small file per photo, per camera, so an interrupted browse keeps everything it already read. */
class FileCatalogCache(context: Context) : CatalogCache {
    private val root = File(context.applicationContext.noBackupFilesDir, "catalog")

    private fun folder(address: String) = File(root, address.replace(":", ""))

    override suspend fun load(address: String): List<CatalogEntry> = withContext(Dispatchers.IO) {
        folder(address).listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().mapNotNull { file ->
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val handle = input.readInt()
                    val name = input.readUTF()
                    val size = input.readLong()
                    val thumbSize = input.readInt()
                    val thumb = if (thumbSize >= 0) ByteArray(thumbSize).also(input::readFully) else null
                    // Files written before orientation was kept simply end here.
                    val orientation = if (input.available() > 0) input.readInt() else 0
                    val capturedAt = if (input.available() > 0) input.readUTF() else ""
                    val pixelWidth = if (input.available() > 0) input.readInt() else 0
                    val pixelHeight = if (input.available() > 0) input.readInt() else 0
                    CatalogEntry(handle, name, size, thumb, orientation, capturedAt, pixelWidth, pixelHeight)
                }
            }.getOrNull()
        }
    }

    override suspend fun put(address: String, entry: CatalogEntry): Unit = withContext(Dispatchers.IO) {
        val dir = folder(address).apply { mkdirs() }
        val temp = File(dir, "${entry.handle}.tmp")
        DataOutputStream(temp.outputStream().buffered()).use { out ->
            out.writeInt(entry.handle)
            out.writeUTF(entry.name)
            out.writeLong(entry.sizeBytes)
            out.writeInt(entry.thumbnail?.size ?: -1)
            entry.thumbnail?.let(out::write)
            out.writeInt(entry.orientation)
            out.writeUTF(entry.capturedAt)
            out.writeInt(entry.pixelWidth)
            out.writeInt(entry.pixelHeight)
        }
        temp.renameTo(File(dir, "${entry.handle}$SUFFIX"))
    }

    override suspend fun retain(address: String, handles: Set<Int>): Unit = withContext(Dispatchers.IO) {
        folder(address).listFiles().orEmpty().forEach { file ->
            if (!file.isDirectory && file.name != FINGERPRINT && file.name.removeSuffix(SUFFIX).toIntOrNull() !in handles) file.delete()
        }
    }

    override suspend fun clear(address: String): Unit = withContext(Dispatchers.IO) {
        folder(address).deleteRecursively()
        Unit
    }

    private fun previewFile(address: String, name: String) = File(File(folder(address), "previews"), "$name.jpg")

    override suspend fun loadPreview(address: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { previewFile(address, name).takeIf { it.exists() }?.readBytes() }.getOrNull()
    }

    override suspend fun savePreview(address: String, name: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val file = previewFile(address, name).apply { parentFile?.mkdirs() }
            val smaller = shrink(bytes)
            file.writeBytes(if (smaller != null && smaller.size < bytes.size) smaller else bytes)
        }
    }

    /** Short side down to phone width, stronger JPEG. The camera's orientation is kept in the catalog entry. */
    private fun shrink(bytes: ByteArray): ByteArray? {
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val short = minOf(decoded.width, decoded.height)
        val scaled = if (short > PREVIEW_SHORT_SIDE) {
            android.graphics.Bitmap.createScaledBitmap(decoded, decoded.width * PREVIEW_SHORT_SIDE / short, decoded.height * PREVIEW_SHORT_SIDE / short, true)
        } else decoded
        return java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, it) }.toByteArray()
    }

    override suspend fun loadFingerprint(address: String): CardFingerprint? = withContext(Dispatchers.IO) {
        runCatching {
            val lines = File(folder(address), FINGERPRINT).readLines()
            val counts = lines.drop(1).associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
            CardFingerprint(lines.first().split(',').filter { it.isNotEmpty() }.map { it.toInt() }, counts)
        }.getOrNull()
    }

    override suspend fun saveFingerprint(address: String, fingerprint: CardFingerprint): Unit = withContext(Dispatchers.IO) {
        val dir = folder(address).apply { mkdirs() }
        val temp = File(dir, "$FINGERPRINT.tmp")
        temp.writeText((listOf(fingerprint.handles.joinToString(",")) + fingerprint.countsByDate.map { (date, count) -> "$date=$count" }).joinToString("\n"))
        temp.renameTo(File(dir, FINGERPRINT))
    }

    private companion object {
        const val FINGERPRINT = "fingerprint.txt"
        const val PREVIEW_SHORT_SIDE = 1080
        const val PREVIEW_QUALITY = 65
        const val SUFFIX = ".entry"
    }
}
