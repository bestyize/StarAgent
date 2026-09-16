package com.starringbox.staragent.core.platform.media

import com.starringbox.staragent.core.model.MediaAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.SwingUtilities

/**
 * 桌面端保存：弹系统"另存为"对话框。
 * 和选图一样，对话框必须在 AWT 事件线程上弹。
 */
class DesktopMediaSaver : MediaSaver {

    override suspend fun save(asset: MediaAsset, suggestedName: String): String? =
        withContext(Dispatchers.IO) {
            var target: File? = null

            SwingUtilities.invokeAndWait {
                val dialog = FileDialog(null as Frame?, "保存图片", FileDialog.SAVE)
                dialog.file = suggestedName
                dialog.isVisible = true
                val directory = dialog.directory
                val name = dialog.file
                if (directory != null && name != null) {
                    target = File(directory, name)
                }
            }

            val file = target ?: return@withContext null
            runCatching {
                file.writeBytes(asset.bytes)
                file.absolutePath
            }.getOrNull()
        }
}
