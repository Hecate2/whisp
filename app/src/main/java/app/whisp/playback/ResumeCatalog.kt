// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.whisp.data.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream

/** System browsing reads indexed metadata and cached art only; it never opens a NAS connection. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class ResumeCatalog(private val repository: LibraryRepository) {
    data class Snapshot(val work: WorkEntity, val tracks: List<TrackEntity>, val position: QueuePosition)

    suspend fun snapshot(): Snapshot? {
        repository.flushPlaybackProgress()
        val progress = repository.dao.latestProgress() ?: return null
        val detail = repository.dao.details(progress.workId) ?: return null
        val tracks = TrackSections.queue(detail.tracks, progress.trackId)
        if (tracks.isEmpty()) return null
        return Snapshot(detail.work, tracks, PlaybackQueue.resume(tracks, progress))
    }

    suspend fun recentItem(snapshot: Snapshot): MediaItem {
        val hide = repository.settingsUpdates.first().hideArtwork
        val track = snapshot.tracks[snapshot.position.index]
        val metadata = repository.mediaMetadata(track, snapshot.work, hide).buildUpon().setArtworkUri(null)
        // A private file URI cannot be opened by SystemUI's legacy browser. Embed a small thumbnail.
        if (!hide) thumbnail(snapshot.work.coverId)?.let {
            metadata.setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return MediaItem.Builder().setMediaId(track.id).setMediaMetadata(metadata.build()).build()
    }

    private fun thumbnail(id: String?): ByteArray? {
        val file = repository.artwork(id) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 320)
            options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null
        return try {
            val output = ByteArrayOutputStream()
            @Suppress("DEPRECATION") val format = if (Build.VERSION.SDK_INT >= 30)
                Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
            if (!bitmap.compress(format, 80, output)) null
            else output.toByteArray().takeIf { it.size <= 64 * 1024 }
        } finally { bitmap.recycle() }
    }
}
