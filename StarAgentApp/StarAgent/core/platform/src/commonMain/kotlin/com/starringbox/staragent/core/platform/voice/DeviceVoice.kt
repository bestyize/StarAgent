package com.starringbox.staragent.core.platform.voice

/**
 * 设备语音能力的统一安装入口。
 *
 * 语音有两条路，这里装的是**设备那一路**（系统识别 / 系统合成 / 录音 / 播放）。
 * 另一条走大模型服务商，由 `core:data` 调 [Voice.installModel] 装。两条互不覆盖：
 * 设备那路不要 Key、不花钱，能用就该优先；模型那路是补位，也是桌面上唯一可用的那条。
 * 具体走哪条由 [Voice.preference] 决定，为 null（默认）时优先设备。
 *
 * ## 这个 object 本身没有成员
 *
 * `install` 是三端各写一份的**扩展函数**，签名各随其平台：
 *
 * ```
 * androidMain    fun DeviceVoice.install(context: Context, caller: ActivityResultCaller)
 * jvmMain        fun DeviceVoice.install()
 * iosMain        fun DeviceVoice.install()
 * ```
 *
 * 调用点因此读起来是统一的：`DeviceVoice.install(applicationContext, this)` 与
 * `DeviceVoice.install()`。而且 iOS 的调用点和桌面同形——将来把 iOS 接上时，
 * 入口那一行不用改。
 *
 * ⚠️ 调用方要**显式 import 这个扩展函数**：
 *
 * ```
 * import com.starringbox.staragent.core.platform.voice.DeviceVoice
 * import com.starringbox.staragent.core.platform.voice.install
 * ```
 *
 * 只导入 `DeviceVoice` 是不够的——扩展函数不会跟着接收者类型一起进来。
 * 编译器只会说一句 `Unresolved reference 'install'`，不算难查，但没必要让人踩一次。
 *
 * **为什么不用 `expect`/`actual`**：Android 需要 `Context` 和一个能注册权限回调的
 * `ActivityResultCaller`，桌面和 iOS 什么都不需要，而 `expect` 的签名必须完全一致，
 * 表达不了这个差异。硬凑只能把 `Activity` 藏进全局变量——那会悄悄破坏
 * "只能在 Activity 初始化阶段注册"这条约束（见 Android 那份的说明），
 * 而且编译器再也帮不上忙。
 *
 * ## 三条要守住的事
 *
 * 1. **每个进程调一次，且要在第一帧读到 [Voice.canListen] / [Voice.canSpeak] 之前。**
 *    界面拿这两个值决定语音入口显不显示，装晚了首屏就会缺一块。
 * 2. **Android 必须在 `Activity.onCreate` 里调**，理由见 Android 那份 `install`。
 * 3. **什么都不装是合法且有意义的**，不是错误状态。桌面没有系统识别与合成，iOS 同理；
 *    这时模型那一路仍然在。两路都没有时 [Voice.canListen] / [Voice.canSpeak] 为 false，
 *    界面应当**隐藏入口**，而不是留一个点了没反应的按钮——"没有能力就别摆按钮"
 *    是这个项目一贯的约定。
 */
object DeviceVoice
