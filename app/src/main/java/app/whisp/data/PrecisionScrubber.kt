// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.data

import kotlin.math.abs

/** A single drag commits once. Changing scale rebases the finger without jumping in time. */
class PrecisionScrubber(private val duration: Long, private val width: Float,
    startX: Float, private val startY: Float, private val threshold: Float) {
    var positionMs = (duration * (startX / width.coerceAtLeast(1f))).toLong().coerceIn(0, duration)
        private set
    var fine = false
        private set
    var rangeStart = 0L
        private set
    var rangeEnd = duration
        private set
    private var originX = startX
    private var originPosition = positionMs
    fun move(x: Float, y: Float): Long {
        val nextFine = abs(y - startY) >= threshold
        if (nextFine != fine) {
            fine = nextFine
            originX = x; originPosition = positionMs
            val span = duration.coerceAtMost(60_000)
            rangeStart = if (fine) (positionMs - span / 2).coerceIn(0, duration - span) else 0
            rangeEnd = if (fine) rangeStart + span else duration
        } else {
            positionMs = (originPosition + (x - originX) / width.coerceAtLeast(1f) * (rangeEnd - rangeStart))
                .toLong().coerceIn(rangeStart, rangeEnd)
        }
        return positionMs
    }
}
