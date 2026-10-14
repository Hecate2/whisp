// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import app.whisp.data.Cue

internal data class BookmarkDraft(
    val trackId: String,
    val position: Long,
    val end: Long?,
    val title: String,
) {
    companion object {
        fun fromCue(trackId: String, cue: Cue, offsetMs: Long): BookmarkDraft =
            BookmarkDraft(
                trackId,
                (cue.startMs + offsetMs).coerceAtLeast(0),
                (cue.endMs + offsetMs).takeIf { cue.endMs != Long.MAX_VALUE },
                cue.text,
            )

        fun atPosition(trackId: String, positionMs: Long, title: String): BookmarkDraft =
            BookmarkDraft(trackId, positionMs, null, title)
    }
}
