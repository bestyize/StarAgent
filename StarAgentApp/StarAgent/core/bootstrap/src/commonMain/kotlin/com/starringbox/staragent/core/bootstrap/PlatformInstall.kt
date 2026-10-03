package com.starringbox.staragent.core.bootstrap

/**
 * 各端平台能力的统一装配入口。
 *
 * 应用启动时**调一次**，之后 `LocalStores` / `MediaPickers` / `MediaSavers` /
 * `Recorders` / `Players` / `Voice` 这些注册表就都装好了，界面和数据层直接读它们即可。
 *
 * ## 三端的签名
 *
 * ```
 * androidMain    fun PlatformInstall.installProcess(context: Context)   // Application.onCreate
 *                fun PlatformInstall.install(context: Context, caller: ActivityResultCaller)
 * jvmMain        fun PlatformInstall.install()
 * iosMain        fun PlatformInstall.install()
 * ```
 *
 * 和 `DeviceVoice.install` 一样：Android 那两个参数没法藏进一个共同签名里，
 * 藏了就会悄悄破坏"只能在 Activity 初始化阶段注册"这条约束。
 *
 * ⚠️ 调用方要**显式 import 这个扩展函数**（`import ...core.bootstrap.install`）——
 * 只导入 `PlatformInstall` 是不够的。
 *
 * ## 为什么要单独一层
 *
 * "实现"和"把实现装上去"是两件事。各端的实现在 `core:platform` 自己的源集里，
 * 这里只管**什么时候、按什么顺序**把它们装进注册表。分开之后：
 *
 * - 各端入口只剩一行，漏装一个会很明显（而不是散在几行里看不出来）；
 * - Android 那串有顺序讲究的调用（Context 必须最先、`registerForActivityResult`
 *   必须在 `onCreate` 里）集中在一处，而不是散在 Activity 里；
 * - 新增一类平台能力时，只改这里和 `core:platform`，不用碰三端入口。
 */
object PlatformInstall
