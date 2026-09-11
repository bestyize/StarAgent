package com.starringbox.staragent

import androidx.compose.runtime.Composable

/** 桌面端没有系统返回键，窗口关闭由系统负责。 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
