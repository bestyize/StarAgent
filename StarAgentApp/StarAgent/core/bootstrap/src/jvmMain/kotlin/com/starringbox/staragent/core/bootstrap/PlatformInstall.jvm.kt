package com.starringbox.staragent.core.bootstrap

import com.starringbox.staragent.core.platform.media.DesktopMediaPicker
import com.starringbox.staragent.core.platform.media.DesktopMediaSaver
import com.starringbox.staragent.core.platform.media.MediaPickers
import com.starringbox.staragent.core.platform.media.MediaSavers
import com.starringbox.staragent.core.platform.storage.DesktopLocalStore
import com.starringbox.staragent.core.platform.storage.LocalStores
import com.starringbox.staragent.core.platform.voice.DeviceVoice
import com.starringbox.staragent.core.platform.voice.install

/**
 * 桌面：把这一端的能力一次装齐。
 *
 * 在 `main()` 里调一次即可，没有 Android 那种"必须在某个生命周期阶段"的限制——
 * `javax.sound.sampled` 和 `java.awt.FileDialog` 都不需要注册结果回调。
 *
 * 没有签名参数是因为桌面确实什么都不需要：不像 Android 要 `Context`，
 * 也不像它有必须在 `onCreate` 完成的注册。凡是需要的平台，签名里都会体现出来。
 *
 * 装完之后仍然可能"不可用"：这台机器没有麦克风、没有声卡、或者没有图形环境，
 * 对应的实现会把 `available` 如实报成 false，界面据此隐藏入口——
 * 那是正常运行状态，不是失败。
 */
fun PlatformInstall.install() {
    LocalStores.install(DesktopLocalStore())
    MediaPickers.install(DesktopMediaPicker())
    MediaSavers.install(DesktopMediaSaver())

    // 桌面只有录音和播放；识别与合成在 JDK 里没有系统级的对应物，走模型那一路
    DeviceVoice.install()
}
