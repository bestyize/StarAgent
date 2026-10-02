package com.starringbox.staragent

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformSystemBars(dark: Boolean) = Unit

@Composable
actual fun PlatformBackgroundEffect(onBackground: () -> Unit) = Unit
