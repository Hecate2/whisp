// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

/** Sleep-timer deadline math on a caller-supplied clock, so it stays unit-testable. */
internal object SleepTimer {
    fun deadlineMs(nowMs: Long, minutes: Int): Long {
        val clamped = minutes.coerceIn(0, 180)
        return if (clamped == 0) 0 else nowMs + clamped * 60_000L
    }
}
