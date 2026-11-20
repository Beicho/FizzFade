package io.github.qishui.automix.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** HyperOS colours following the system dark mode; Monet (wallpaper) colours on request. */
@Composable
fun AppTheme(monet: Boolean, content: @Composable () -> Unit) {
    val controller = remember(monet) {
        ThemeController(if (monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System)
    }
    MiuixTheme(controller = controller, content = content)
}

/** Accent tiles behind row icons, as in the HyperOS settings app. */
object Tint {
    val Blue = Color(0xFF3482FF)
    val Indigo = Color(0xFF5B6CFF)
    val Pink = Color(0xFFFF4F7B)
    val Orange = Color(0xFFFF9500)
    val Purple = Color(0xFF9B5CFF)
    val Green = Color(0xFF26C266)
    val Teal = Color(0xFF14B8C4)
    val Red = Color(0xFFFF4D4F)
    val Gray = Color(0xFF8E8E93)
}

/** Status hero colours: background, big glyph, in light and dark. */
data class HeroColors(val background: Color, val glyph: Color)

@Composable
fun heroColors(state: io.github.qishui.automix.ModuleStatus.State): HeroColors {
    val dark = isSystemInDarkTheme()
    val scheme = MiuixTheme.colorScheme
    if (MiuixTheme.isDynamicColor) {
        return when (state) {
            io.github.qishui.automix.ModuleStatus.State.CONNECTED ->
                HeroColors(scheme.secondaryContainer, scheme.primary.copy(alpha = 0.8f))
            else -> HeroColors(scheme.surfaceContainer, scheme.onSurfaceVariantSummary.copy(alpha = 0.35f))
        }
    }
    return when (state) {
        io.github.qishui.automix.ModuleStatus.State.CONNECTED ->
            if (dark) HeroColors(Color(0xFF15331F), Color(0xFF30D158)) else HeroColors(Color(0xFFDFF7E6), Color(0xFF34C759))
        io.github.qishui.automix.ModuleStatus.State.WAITING ->
            if (dark) HeroColors(Color(0xFF3A2C12), Color(0xFFFF9F0A)) else HeroColors(Color(0xFFFFF1DB), Color(0xFFFF9500))
        io.github.qishui.automix.ModuleStatus.State.NOT_INSTALLED ->
            if (dark) HeroColors(Color(0xFF3B1818), Color(0xFFFF453A)) else HeroColors(Color(0xFFFFE6E4), Color(0xFFFF3B30))
        else ->
            if (dark) HeroColors(Color(0xFF24262B), Color(0xFF8E8E93)) else HeroColors(Color(0xFFECEFF4), Color(0xFFA1A6B3))
    }
}

/** Frosted bar material, the values SukiSU/KernelSU ship (blur 20 dp at 35 % input scale). */
@OptIn(ExperimentalHazeApi::class)
fun Modifier.barHaze(state: HazeState, style: HazeStyle): Modifier = hazeEffect(state = state, style = style) {
    blurRadius = 20.dp
    inputScale = HazeInputScale.Fixed(0.35f)
    noiseFactor = 0f
    forceInvalidateOnPreDraw = false
}

/** HyperOS top bar: full blur at the status bar fading out at the bar's lower edge. */
fun Modifier.topBarHaze(state: HazeState, style: HazeStyle): Modifier = hazeEffect(state = state, style = style) {
    blurRadius = 24.dp
    noiseFactor = 0f
    progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f, preferPerformance = true)
}

@Composable
fun rememberBarStyle(alpha: Float = 0.8f): HazeStyle {
    val surface = MiuixTheme.colorScheme.surface
    return remember(surface, alpha) { HazeStyle(backgroundColor = surface, tint = HazeTint(surface.copy(alpha = alpha))) }
}
