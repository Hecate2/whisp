// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import java.util.UUID

/** A renderer input format snapshot, detached from Media3 types for testability. */
internal data class FormatSnapshot(val mime: String?, val codecs: String?)

/**
 * Pairs each decoder initialization with the input format event from the same
 * renderer call. Renderers initialize the codec before dispatching the format
 * event, so recording at init time would read a stale or missing format.
 *
 * Init and its format stay adjacent in the per-kind event stream: an init
 * without a following format is always an in-place recovery, where the cached
 * format is still current. A provisional entry therefore never carries a stale
 * format, even if no format event ever consumes it.
 *
 * Every emission carries a stable id so the sink can replace the provisional
 * entry in place, no matter what was recorded after it.
 */
internal class DecoderCorrelator(
    private val sink: (
        kind: String,
        snapshot: FormatSnapshot?,
        decoder: String?,
        id: String,
        replaceProvisional: Boolean,
    ) -> Unit,
) {
    private val cached = mutableMapOf<String, FormatSnapshot?>()
    private val lastDecoder = mutableMapOf<String, String?>()
    private val pending = mutableMapOf<String, String>()

    fun onDecoderInitialized(kind: String, decoder: String) {
        lastDecoder[kind] = decoder
        val id = UUID.randomUUID().toString()
        pending[kind] = id
        sink(kind, cached[kind], decoder, id, false)
    }

    fun onFormatChanged(kind: String, snapshot: FormatSnapshot?) {
        cached[kind] = snapshot
        val pendingId = pending.remove(kind)
        val id = pendingId ?: UUID.randomUUID().toString()
        sink(kind, snapshot, lastDecoder[kind], id, pendingId != null)
    }
}
