package com.starringbox.staragent

import androidx.compose.runtime.Composable

/**
 * 系统级返回。
 *
 * 只有 Android 有实体/手势返回键，且它默认归 Activity 管——
 * 不拦下来的话，在二级页按返回会直接退出 App，而不是回到上一页。
 *
 * [enabled] 为 false 时不拦截，交回系统（也就是退出），
 * 所以"栈里还有上一页"这个判断由调用方给。
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
