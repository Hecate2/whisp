// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import app.whisp.playback.DecoderEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PlaybackDiagnosticsTest {
    private fun diagnostics() =
        PlaybackDiagnostics(RuntimeEnvironment.getApplication()).also { it.clear() }

    @Test
    fun recordedEventsRoundTrip() {
        val diagnostics = diagnostics()
        diagnostics.record(DecoderEvent("audio", "audio/mp4a-latm", "mp4a.40.2", "c2 decoder", true))
        diagnostics.record(DecoderEvent("offload", null, null, null, null, true))
        val snapshot = diagnostics.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals("c2 decoder", snapshot[0].decoder)
        assertEquals(true, snapshot[0].hardwareAccelerated)
        assertEquals(true, snapshot[1].offloaded)
        assertNull(snapshot[1].mime)
    }

    @Test
    fun onlyTheLatestEventsAreKept() {
        val diagnostics = diagnostics()
        repeat(70) { index ->
            diagnostics.record(DecoderEvent("audio", "audio/mp4a-latm", null, "decoder-$index", true))
        }
        val snapshot = diagnostics.snapshot()
        assertEquals(64, snapshot.size)
        assertEquals("decoder-6", snapshot.first().decoder)
        assertEquals("decoder-69", snapshot.last().decoder)
    }
}
