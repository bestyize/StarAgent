package com.starringbox.staragent.core.media

/**
 * 选取一张图片。
 *
 * 做成"平台注册、上层取用"，而不是 expect/actual——
 * Android 的选图必须挂在 Activity 的 result 回调上，靠 expect 函数凭空造一个是做不到的。
 * 由各端入口把实现注入进来，依赖方向也更顺：平台推给媒体层，媒体层不认识任何平台。
 */
interface MediaPicker {
    /** 用户取消或平台不支持时返回 null。 */
    suspend fun pickImage(): MediaAsset?
}

/** 未实现选图的平台，走这里。 */
private object UnavailablePicker : MediaPicker {
    override suspend fun pickImage(): MediaAsset? = null
}

object MediaPickers {

    private var installed: MediaPicker = UnavailablePicker

    /** 各端入口调用一次。桌面在 main()，Android 在 MainActivity，iOS 在 MainViewController。 */
    fun install(picker: MediaPicker) {
        installed = picker
    }

    /** 当前平台能不能选图。界面据此决定是显示相机入口还是直接隐藏。 */
    val available: Boolean get() = installed !== UnavailablePicker

    suspend fun pickImage(): MediaAsset? = installed.pickImage()
}
