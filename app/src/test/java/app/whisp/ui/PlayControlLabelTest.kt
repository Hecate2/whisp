// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayControlLabelTest {
    @Test
    fun playingShowsPause() {
        assertEquals("暂停", playControlLabel(PlaybackState(playWhenReady = true)))
    }

    @Test
    fun failedShowsRetry() {
        assertEquals("重试播放", playControlLabel(PlaybackState(failed = true)))
    }

    @Test
    fun endedShowsReplay() {
        assertEquals("重新播放作品", playControlLabel(PlaybackState(ended = true)))
    }

    @Test
    fun idleShowsPlay() {
        assertEquals("播放", playControlLabel(PlaybackState()))
    }
}
