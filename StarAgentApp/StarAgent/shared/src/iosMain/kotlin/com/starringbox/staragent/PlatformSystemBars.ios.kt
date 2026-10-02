package com.starringbox.staragent

import androidx.compose.runtime.Composable

// The iOS host owns its status bar appearance.
@Composable
actual fun PlatformSystemBars(dark: Boolean) = Unit

@Composable
actual fun PlatformBackgroundEffect(onBackground: () -> Unit) = Unit
