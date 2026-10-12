// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import kotlin.math.abs

/** A seek or restored follow must not scroll through the intervening script. */
internal object SubtitleFollow {
    fun animate(previous: Int, target: Int, visible: Boolean, reduced: Boolean): Boolean =
        !reduced && visible && previous >= 0 && target >= 0 && abs(target - previous) == 1
}
