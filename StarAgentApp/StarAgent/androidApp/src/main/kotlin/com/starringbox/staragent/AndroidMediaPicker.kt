package com.starringbox.staragent

import android.content.Context
import android.net.Uri
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.starringbox.staragent.core.media.MediaAsset
import com.starringbox.staragent.core.media.MediaPicker
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Android 选图，用系统相册选择器。
 *
 * 注意 `registerForActivityResult` 只能在 Activity 初始化阶段调用，
 * 所以这个类由 MainActivity 在 onCreate 里构造并注入，不能等用到时再 new。
 */
class AndroidMediaPicker(
    private val context: Context,
    caller: ActivityResultCaller,
) : MediaPicker {

    private var pending: CancellableContinuation<MediaAsset?>? = null

    private val launcher = caller.registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val continuation = pending
        pending = null
        continuation?.resume(uri?.let { readAsset(context, it) })
    }

    override suspend fun pickImage(): MediaAsset? = suspendCancellableCoroutine { continuation ->
        pending = continuation
        continuation.invokeOnCancellation { pending = null }
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}

/** 从 content:// 读出字节与 MIME；读不到就返回 null，由界面提示。 */
private fun readAsset(context: Context, uri: Uri): MediaAsset? = runCatching {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "image/jpeg"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    MediaAsset(
        id = UUID.randomUUID().toString(),
        mimeType = mimeType,
        bytes = bytes,
    )
}.getOrNull()
