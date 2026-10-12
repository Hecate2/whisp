// SPDX-License-Identifier: LGPL-3.0-or-later
package app.whisp.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF17101F)
val Surface = Color(0xFF24192D)
val Raised = Color(0xFF33253E)
val Rose = Color(0xFFEE9EBE)
val Cream = Color(0xFFF9EDF3)
val Muted = Color(0xFFBBA9C5)

internal val LocalReducedMotion = staticCompositionLocalOf { false }

// Spatial motion has no overshoot; sheet dismissal is faster than expansion.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class WhispMotionScheme(private val reduced: Boolean) : MotionScheme {
    private fun <T> spatial(ms: Int): FiniteAnimationSpec<T> = tween(if (reduced) 0 else ms, easing = FastOutSlowInEasing)
    override fun <T> defaultSpatialSpec() = spatial<T>(260)
    override fun <T> fastSpatialSpec() = spatial<T>(180)
    override fun <T> slowSpatialSpec() = spatial<T>(280)
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(100)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(if (reduced) 0 else 180)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(160)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun WhispTheme(reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalReducedMotion provides reduceMotion) {
        MaterialTheme(colorScheme = darkColorScheme(primary = Rose, onPrimary = Ink,
            background = Ink, onBackground = Cream, surface = Surface, onSurface = Cream,
            surfaceVariant = Raised, onSurfaceVariant = Muted, secondary = Rose,
            outline = Muted.copy(alpha = .3f)), motionScheme = remember(reduceMotion) { WhispMotionScheme(reduceMotion) }, content = content)
    }
}
