// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

/** A renderer input format snapshot, detached from Media3 types for testability. */
internal data class FormatSnapshot(val mime: String?, val codecs: String?)

/**
 * Pairs each decoder initialization with the input format event from the same
 * renderer call. Renderers initialize the codec before dispatching the format
 * event, so recording at init time would read a stale or missing format.
 */
internal class DecoderCorrelator(
    private val sink: (
        kind: String,
        snapshot: FormatSnapshot?,
        decoder: String?,
        replaceProvisional: Boolean,
    ) -> Unit,
) {
    private val cached = mutableMapOf<String, FormatSnapshot?>()
    private val lastDecoder = mutableMapOf<String, String?>()
    private val provisional = mutableSetOf<String>()

    fun onDecoderInitialized(kind: String, decoder: String) {
        lastDecoder[kind] = decoder
        provisional += kind
        sink(kind, cached[kind], decoder, false)
    }

    fun onFormatChanged(kind: String, snapshot: FormatSnapshot?) {
        cached[kind] = snapshot
        sink(kind, snapshot, lastDecoder[kind], provisional.remove(kind))
    }
}
