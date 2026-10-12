// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

data class QueuePosition(val index: Int, val positionMs: Long)

object PlaybackQueue {
    fun finished(tracks: List<TrackEntity>, progress: ProgressEntity?): Boolean {
        val last = tracks.lastOrNull() ?: return false
        return progress?.trackId == last.id && last.durationMs > 0 && progress.positionMs > 0 && progress.positionMs >= last.durationMs - 500
    }
    fun resume(tracks: List<TrackEntity>, progress: ProgressEntity?): QueuePosition {
        val index = tracks.indexOfFirst { it.id == progress?.trackId }
        if (index < 0) return QueuePosition(0, 0)
        val position = progress!!.positionMs.coerceAtLeast(0)
        val duration = tracks[index].durationMs
        return if (duration > 0 && position > 0 && position >= duration - 500)
            QueuePosition(if (index == tracks.lastIndex) 0 else index + 1, 0)
        else QueuePosition(index, position)
    }
}
