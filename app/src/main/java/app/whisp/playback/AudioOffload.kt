// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import androidx.media3.common.TrackSelectionParameters

/** DSP audio offload keeps screen-off playback off the application processor. */
internal object AudioOffload {
    fun supported(sdkInt: Int): Boolean = sdkInt >= 29

    fun preferences(): TrackSelectionParameters.AudioOffloadPreferences =
        TrackSelectionParameters.AudioOffloadPreferences.Builder()
            .setAudioOffloadMode(
                TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED,
            )
            .setIsGaplessSupportRequired(true)
            .setIsSpeedChangeSupportRequired(false)
            .build()
}
