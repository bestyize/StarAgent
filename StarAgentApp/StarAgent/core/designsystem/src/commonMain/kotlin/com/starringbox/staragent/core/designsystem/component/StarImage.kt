package com.starringbox.staragent.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.starringbox.staragent.core.designsystem.theme.StarPalette

/**
 * 直接渲染一段图片字节。
 *
 * 刻意只收 [ByteArray] 而不是媒体模块的 MediaAsset——
 * 设计系统不该反过来依赖业务类型，这样它仍然是纯粹的可视层。
 */
@Composable
fun StarImage(
    bytes: ByteArray,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap = remember(bytes) {
        runCatching { bytes.decodeToImageBitmap() }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        // 解码失败时不崩，退化成一个占位块
        Box(modifier.background(StarPalette.SurfaceSunken))
    }
}
