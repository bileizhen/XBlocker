package io.github.xblocker.ui.theme

import androidx.compose.runtime.Composable
import io.github.xblocker.ui.LocalDarkTheme

@Composable
fun isInDarkTheme(): Boolean = LocalDarkTheme.current
