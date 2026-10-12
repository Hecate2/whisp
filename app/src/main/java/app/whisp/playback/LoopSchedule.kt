// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.playback

/** Preserve the 40 ms boundary check, while avoiding 25 checks/s for a long segment. */
internal object LoopSchedule {
    fun delayMs(remainingMs: Long, speed: Float): Long {
        val remainingRealMs = (remainingMs / speed.coerceAtLeast(.1f)).toLong()
        return (remainingRealMs - 120).coerceIn(40, 1000)
    }
}
