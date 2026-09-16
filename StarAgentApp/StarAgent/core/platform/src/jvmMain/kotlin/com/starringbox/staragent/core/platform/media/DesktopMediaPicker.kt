package com.starringbox.staragent.core.platform.media

import com.starringbox.staragent.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID
import javax.swing.SwingUtilities

/**
 * 桌面端选图：用 AWT 的原生文件对话框。
 *
 * 对话框必须在 AWT 事件线程上弹，所以走 invokeAndWait；
 * 外层切到 IO 调度器，避免把调用方的协程卡住。
 */
class DesktopMediaPicker : MediaPicker {

    override suspend fun pickImage(): MediaAsset? = withContext(Dispatchers.IO) {
        var chosen: File? = null

        SwingUtilities.invokeAndWait {
            val dialog = FileDialog(null as Frame?, "选择图片", FileDialog.LOAD)
            dialog.isMultipleMode = false
            dialog.isVisible = true
            val directory = dialog.directory
            val name = dialog.file
            if (directory != null && name != null) {
                chosen = File(directory, name)
            }
        }

        val file = chosen
        if (file == null || !file.isFile) return@withContext null

        MediaAsset(
            id = UUID.randomUUID().toString(),
            mimeType = mimeTypeOf(file.name),
            bytes = file.readBytes(),
        )
    }
}

/** 按扩展名推断 MIME。内容真伪由各家服务商自己判断，这里只做声明。 */
private fun mimeTypeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }
