package com.starringbox.staragent.core.platform.media

import com.starringbox.staragent.core.model.MediaAsset

/**
 * 把一份媒体资源存到本地。
 *
 * 和 [MediaPicker] 一样是"平台注册、上层取用"——桌面弹系统保存框、
 * Android 走系统的创建文档流程，都做不到用 expect 函数凭空实现。
 */
interface MediaSaver {
    /**
     * 保存成功返回落盘位置（路径或 URI，仅用于展示），用户取消或失败返回 null。
     */
    suspend fun save(asset: MediaAsset, suggestedName: String): String?
}

/** 未实现保存的平台，走这里。 */
private object UnavailableSaver : MediaSaver {
    override suspend fun save(asset: MediaAsset, suggestedName: String): String? = null
}

object MediaSavers {

    private var installed: MediaSaver = UnavailableSaver

    fun install(saver: MediaSaver) {
        installed = saver
    }

    /** 当前平台能不能保存。界面据此决定要不要显示保存入口。 */
    val available: Boolean get() = installed !== UnavailableSaver

    suspend fun save(asset: MediaAsset, suggestedName: String): String? =
        installed.save(asset, suggestedName)
}

/**
 * 按 MIME 给个合理的扩展名。
 * 各家服务商只接受那几种图片格式，所以这里的映射是封闭的。
 */
fun MediaAsset.suggestedFileName(prefix: String = "staragent"): String {
    val extension = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "jpg"
    }
    return "$prefix-$id.$extension"
}
