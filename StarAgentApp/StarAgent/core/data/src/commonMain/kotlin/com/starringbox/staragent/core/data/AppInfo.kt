package com.starringbox.staragent.core.data

/**
 * 应用自身的元信息，目前只有设置页的「关于」在用。
 *
 * [VERSION] 和 `androidApp/build.gradle.kts` 里的 `versionName` 是同一个东西，
 * **两边都要改**。没有做成一份自动同步，是因为三端各自有自己的版本声明方式
 * （Android 走 BuildConfig，桌面和 iOS 是各自的打包配置），
 * 为了一个显示用的字符串去搭一层构建期注入不划算。等真的需要按版本做逻辑时再搭。
 */
object AppInfo {
    const val NAME = "星环智能 Agent"
    const val VERSION = "1.0"
}
