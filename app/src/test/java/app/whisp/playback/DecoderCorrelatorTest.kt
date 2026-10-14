// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class DecoderCorrelatorTest {
    private data class Emission(
        val kind: String,
        val snapshot: FormatSnapshot?,
        val decoder: String?,
        val replace: Boolean,
    )

    private fun correlator(log: MutableList<Emission>) =
        DecoderCorrelator { kind, snapshot, decoder, replace ->
            log += Emission(kind, snapshot, decoder, replace)
        }

    @Test
    fun decoderInitPairsWithItsOwnFormatEvent() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        // Observed order: decoder(AAC) -> format(AAC) -> decoder(FLAC) -> format(FLAC).
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged("audio", FormatSnapshot("audio/aac", null))
        correlator.onDecoderInitialized("audio", "flac-decoder")
        correlator.onFormatChanged("audio", FormatSnapshot("audio/flac", "fLaC"))
        assertEquals(
            listOf(
                Emission("audio", null, "aac-decoder", false),
                Emission("audio", FormatSnapshot("audio/aac", null), "aac-decoder", true),
                Emission("audio", FormatSnapshot("audio/aac", null), "flac-decoder", false),
                Emission("audio", FormatSnapshot("audio/flac", "fLaC"), "flac-decoder", true),
            ),
            log,
        )
    }

    @Test
    fun videoInitPairsWithItsOwnFormatEvent() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged("video", FormatSnapshot("video/avc", "avc1"))
        assertEquals(
            listOf(
                Emission("video", null, "avc-decoder", false),
                Emission("video", FormatSnapshot("video/avc", "avc1"), "avc-decoder", true),
            ),
            log,
        )
    }

    @Test
    fun reusedDecoderKeepsTheLatestFormat() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onFormatChanged("audio", FormatSnapshot("audio/aac", null))
        // No re-init: the continuing decoder still applies.
        correlator.onFormatChanged("audio", FormatSnapshot("audio/aac", "mp4a"))
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", "mp4a"), "aac-decoder", false),
            log.last(),
        )
    }

    @Test
    fun kindsDoNotInterfere() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("audio", "aac-decoder")
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged("audio", FormatSnapshot("audio/aac", null))
        correlator.onFormatChanged("video", FormatSnapshot("video/avc", null))
        assertEquals(
            listOf(
                Emission("audio", null, "aac-decoder", false),
                Emission("video", null, "avc-decoder", false),
                Emission("audio", FormatSnapshot("audio/aac", null), "aac-decoder", true),
                Emission("video", FormatSnapshot("video/avc", null), "avc-decoder", true),
            ),
            log,
        )
    }
}
