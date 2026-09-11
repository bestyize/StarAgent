package com.starringbox.staragent

import android.content.Context
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import com.starringbox.staragent.core.media.MediaAsset
import com.starringbox.staragent.core.media.MediaSaver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Android 保存，走系统的"创建文档"流程（会弹到下载目录或用户选的位置）。
 * 和选图一样必须在 Activity 初始化阶段注册。
 */
class AndroidMediaSaver(
    private val context: Context,
    caller: ActivityResultCaller,
) : MediaSaver {

    private class Pending(
        val asset: MediaAsset,
        val continuation: CancellableContinuation<String?>,
    )

    private var pending: Pending? = null

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/*"),
    ) { uri ->
        val request = pending
        pending = null
        if (request != null) {
            val result = if (uri == null) {
                null // 用户取消
            } else {
                val written = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(request.asset.bytes) }
                }.isSuccess
                if (written) uri.toString() else null
            }
            request.continuation.resume(result)
        }
    }

    override suspend fun save(asset: MediaAsset, suggestedName: String): String? =
        suspendCancellableCoroutine { continuation ->
            pending = Pending(asset, continuation)
            continuation.invokeOnCancellation { pending = null }
            launcher.launch(suggestedName)
        }
}
