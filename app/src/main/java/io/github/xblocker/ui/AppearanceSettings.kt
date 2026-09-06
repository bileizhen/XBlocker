package io.github.xblocker.ui

import androidx.compose.runtime.staticCompositionLocalOf

data class AppearanceSettings(
    val blur: Boolean = true,
    val floatingBar: Boolean = true,
    val liquidGlass: Boolean = true,
    val predictiveBack: Boolean = true,
    val scale: Float = 1f,
)

val LocalDarkTheme = staticCompositionLocalOf { false }
