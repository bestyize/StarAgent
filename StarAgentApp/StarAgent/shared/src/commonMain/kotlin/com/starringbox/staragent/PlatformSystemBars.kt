package com.starringbox.staragent

import androidx.compose.runtime.Composable

/** Keep system icons legible on the companion's selectable background. */
@Composable
expect fun PlatformSystemBars(dark: Boolean)

@Composable
expect fun PlatformBackgroundEffect(onBackground: () -> Unit)
