// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

/** An A–B loop segment validated against the current track. */
internal data class LoopState(val startMs: Long, val endMs: Long) {
    companion object {
        fun validate(
            startMs: Long,
            endMs: Long,
            durationMs: Long?,
            hasMedia: Boolean,
        ): LoopState? {
            val end = if (durationMs == null) endMs else endMs.coerceAtMost(durationMs)
            if (!hasMedia || startMs < 0 || end <= startMs || end - startMs < 500) return null
            return LoopState(startMs, end)
        }
    }
}
