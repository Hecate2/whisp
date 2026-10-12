// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueTest {
    private val tracks =
        listOf(
            TrackEntity("a", "work", "One", "01.mp3", "01.mp3", 1, durationMs = 10000),
            TrackEntity("b", "work", "Two", "02.mp3", "02.mp3", 2, durationMs = 20000),
        )

    @Test
    fun resumesInsideTheRememberedTrack() {
        assertEquals(
            QueuePosition(1, 7000),
            PlaybackQueue.resume(tracks, ProgressEntity("work", "b", 7000)),
        )
    }

    @Test
    fun finishedTrackAdvancesAndFinishedWorkRestarts() {
        assertEquals(
            QueuePosition(1, 0),
            PlaybackQueue.resume(tracks, ProgressEntity("work", "a", 10000)),
        )
        assertEquals(
            QueuePosition(0, 0),
            PlaybackQueue.resume(tracks, ProgressEntity("work", "b", 20000)),
        )
    }

    @Test
    fun removedTrackDoesNotApplyItsOffsetToTheFirstTrack() {
        assertEquals(
            QueuePosition(0, 0),
            PlaybackQueue.resume(tracks, ProgressEntity("work", "missing", 18000)),
        )
    }
}
