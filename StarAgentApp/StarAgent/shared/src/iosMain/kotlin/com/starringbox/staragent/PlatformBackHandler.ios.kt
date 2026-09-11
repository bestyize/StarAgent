package com.starringbox.staragent

import androidx.compose.runtime.Composable

/**
 * iOS 没有系统返回键（只有边缘侧滑手势），所以这里不做事。
 * 页面内的返回箭头正常工作。
 *
 * 【待办】iOS 的侧滑返回手势需要接 UINavigationController 或自己实现，
 * 且必须在 Mac 环境编译验证，暂未接入。
 */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
