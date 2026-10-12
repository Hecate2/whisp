// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.TreeSet
import java.util.UUID

const val MIB = 1024L * 1024

/** Same LRU policy as Media3, with changes applied under SimpleCache's monitor. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class AdjustableCacheEvictor(private var limit: Long, private val changed: () -> Unit = {}) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }
        .thenBy { it.key }.thenBy { it.position })
    private var bytes = 0L
    fun resize(cache: Cache, newLimit: Long) = synchronized(cache) {
        limit = newLimit.coerceAtLeast(0)
        evict(cache, 0)
    }
    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        evict(cache, length.coerceAtLeast(0))
        changed()
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) bytes += span.length
        evict(cache, 0)
        changed()
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) { bytes -= span.length; changed() }
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan)
        spans.add(newSpan)
    }
    private fun evict(cache: Cache, incoming: Long) {
        while (bytes + incoming > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}

internal fun atomicFile(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, file.name + ".${UUID.randomUUID()}.tmp")
    try {
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) throw IOException("无法保存文件")
    } finally { temp.delete() }
}

/** Cache owns only its dedicated directory, never settings, custom art or downloads. */
internal class ImageFileCache(val directory: File, private val limit: Long, private val changed: () -> Unit = {}) {
    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        trim()
    }
    @Synchronized fun get(id: String): File? = File(directory, id).takeIf { it.isFile }?.also {
        val now = System.currentTimeMillis()
        if (now - it.lastModified() > 60_000) it.setLastModified(now)
    }
    @Synchronized fun put(id: String, bytes: ByteArray): File {
        require(bytes.size <= limit) { "图片过大" }
        val file = File(directory, id)
        atomicFile(file, bytes)
        file.setLastModified(System.currentTimeMillis())
        trim(file)
        changed()
        return file
    }
    @Synchronized fun clear() {
        directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        changed()
    }
    @Synchronized fun size() = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    @Synchronized private fun trim(keep: File? = null) {
        val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit) break
            val bytes = file.length()
            if (file != keep && file.delete()) total -= bytes
        }
    }
}

internal object ImageOptimizer {
    @Synchronized fun optimize(original: ByteArray, longEdge: Int = 1600): ByteArray {
        // Preserve small covers byte-for-byte; never recompress an optimized cache entry.
        if (original.size <= 256 * 1024) return original
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return original
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (edge / sample > longEdge * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return original
        var scaled: Bitmap? = null
        try {
            val ratio = minOf(1f, longEdge.toFloat() / maxOf(decoded.width, decoded.height))
            val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1), (decoded.height * ratio).toInt().coerceAtLeast(1), true)
                .also { scaled = it } else decoded
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(format, 88, output)) return original
            return output.toByteArray().takeIf { it.size < original.size } ?: original
        } finally {
            if (scaled !== decoded) scaled?.recycle()
            decoded.recycle()
        }
    }
}

data class StorageUsage(val artwork: Long = 0, val previews: Long = 0, val streaming: Long = 0,
    val offline: Long = 0, val customArtwork: Long = 0, val library: Long = 0) {
    val cache get() = artwork + previews + streaming
    val total get() = cache + offline + customArtwork + library
}
