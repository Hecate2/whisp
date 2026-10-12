// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import org.junit.Assert.*
import org.junit.Test

class SubtitleFollowTest {
    @Test fun adjacentVisibleCuesFollowInEitherDirection() {
        assertTrue(SubtitleFollow.animate(4, 5, true, false))
        assertTrue(SubtitleFollow.animate(5, 4, true, false))
    }
    @Test fun initialRestoredAndDistantPositionsJumpDirectly() {
        assertFalse(SubtitleFollow.animate(-1, 200, true, false))
        assertFalse(SubtitleFollow.animate(1, 200, true, false))
        assertFalse(SubtitleFollow.animate(200, 1, true, false))
        assertFalse(SubtitleFollow.animate(4, 5, false, false))
        assertFalse(SubtitleFollow.animate(4, 4, true, false))
    }
    @Test fun reducedMotionAlwaysPositionsWithoutScrolling() {
        assertFalse(SubtitleFollow.animate(4, 5, true, true))
    }
}
