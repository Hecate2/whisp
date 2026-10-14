// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable

/** A slider whose position is driven by an external value. */
@Composable
internal fun SyncedSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    val sliderState = rememberSliderState(value = value, trackRange = range, steps = steps)
    sliderState.value = value
    Slider(state = sliderState, onValueChange = onValueChange)
}
