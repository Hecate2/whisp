// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

import org.junit.Assert.*
import org.junit.Test

class LoopScheduleTest {
    @Test fun longSegmentsSleepButChecksRemainPreciseNearTheBoundary() {
        assertEquals(1000L, LoopSchedule.delayMs(30_000, 1f))
        assertEquals(880L, LoopSchedule.delayMs(1000, 1f))
        assertEquals(40L, LoopSchedule.delayMs(150, 1f))
        assertEquals(40L, LoopSchedule.delayMs(0, 1f))
        assertEquals(40L, LoopSchedule.delayMs(-100, 1f))
    }
    @Test fun remainingTimeUsesThePlaybackSpeed() {
        assertEquals(380L, LoopSchedule.delayMs(1000, 2f))
        assertEquals(1000L, LoopSchedule.delayMs(1000, .5f))
        assertEquals(40L, LoopSchedule.delayMs(250, 2f))
    }
}
