// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.*
import org.junit.Test

class AudioOffloadTest {
    @Test
    fun offloadRequiresApi29() {
        assertFalse(AudioOffload.supported(26))
        assertFalse(AudioOffload.supported(28))
        assertTrue(AudioOffload.supported(29))
        assertTrue(AudioOffload.supported(35))
    }

    @Test
    fun preferencesStayOffloadFirst() {
        val preferences = AudioOffload.preferences()
        assertEquals(
            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            preferences.audioOffloadMode,
        )
        assertTrue(preferences.isGaplessSupportRequired)
        assertFalse(preferences.isSpeedChangeSupportRequired)
    }
}
