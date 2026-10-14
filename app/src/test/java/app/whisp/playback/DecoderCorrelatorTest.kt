// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class DecoderCorrelatorTest {
    private data class Emission(
        val kind: String,
        val snapshot: FormatSnapshot?,
        val decoder: String?,
        val id: String,
        val replace: Boolean,
    )

    private fun correlator(log: MutableList<Emission>) =
        DecoderCorrelator { kind, snapshot, decoder, id, replace ->
            log += Emission(kind, snapshot, decoder, id, replace)
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
        assertEquals(4, log.size)
        assertEquals(Emission("audio", null, "aac-decoder", log[0].id, false), log[0])
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", null), "aac-decoder", log[0].id, true),
            log[1],
        )
        assertEquals(Emission("audio", FormatSnapshot("audio/aac", null), "flac-decoder", log[2].id, false), log[2])
        assertEquals(
            Emission("audio", FormatSnapshot("audio/flac", "fLaC"), "flac-decoder", log[2].id, true),
            log[3],
        )
        assertNotEquals(log[0].id, log[2].id)
    }

    @Test
    fun videoInitPairsWithItsOwnFormatEvent() {
        val log = mutableListOf<Emission>()
        val correlator = correlator(log)
        correlator.onDecoderInitialized("video", "avc-decoder")
        correlator.onFormatChanged("video", FormatSnapshot("video/avc", "avc1"))
        assertEquals(2, log.size)
        assertEquals(log[0].id, log[1].id)
        assertEquals(
            Emission("video", FormatSnapshot("video/avc", "avc1"), "avc-decoder", log[0].id, true),
            log[1],
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
        assertEquals(3, log.size)
        assertEquals(
            Emission("audio", FormatSnapshot("audio/aac", "mp4a"), "aac-decoder", log[2].id, false),
            log[2],
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
        assertEquals(4, log.size)
        assertEquals(log[0].id, log[2].id)
        assertEquals(log[1].id, log[3].id)
        assertTrue(log[2].replace)
        assertTrue(log[3].replace)
    }
}
