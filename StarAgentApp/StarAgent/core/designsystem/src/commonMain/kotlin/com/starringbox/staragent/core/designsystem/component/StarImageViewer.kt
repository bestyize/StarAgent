package com.starringbox.staragent.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.starringbox.staragent.core.designsystem.icon.StarIconGlyph

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DOUBLE_TAP_WINDOW_MS = 300L

/**
 * 全屏图片查看器。
 *
 * 和 [StarImage] 一样只收字节流，不认识业务类型——所以放在设计系统里，
 * 将来产物卡片、文件预览都能直接复用。
 *
 * 缩放方式有两种，覆盖触屏与鼠标：
 * - **双指捏合**（触屏、触控板）+ 拖动
 * - **双击**在 1 倍与 2.5 倍之间切换——鼠标没有"双指"，桌面端靠这个
 *
 * 缩回 1 倍时复位偏移，避免图片被拖出屏幕找不回来。
 */
@Composable
fun StarImageViewer(
    images: List<ByteArray>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int = 0,
    onSave: ((Int) -> Unit)? = null,
) {
    if (images.isEmpty()) return

    var current by remember(images) { mutableStateOf(initialIndex.coerceIn(0, images.lastIndex)) }
    var scale by remember(current) { mutableStateOf(1f) }
    var offsetX by remember(current) { mutableStateOf(0f) }
    var offsetY by remember(current) { mutableStateOf(0f) }
    var lastTapAt by remember(current) { mutableStateOf(0L) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.96f))
            // 双指缩放 + 拖动（触屏与触控板）
            .pointerInput(current) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    scale = next
                    if (next > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        // 缩回 1 倍时复位，否则图片被拖出屏幕就找不回来了
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            // 双击缩放。鼠标没有"双指"，桌面端只能靠这个；
            // 这里不用 detectTapGestures——它会把事件吃掉，和上面的缩放手势打架。
            .pointerInput(current) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = waitForUpOrCancellation()
                    if (up == null) return@awaitEachGesture
                    val now = up.uptimeMillis
                    val isDoubleTap = now - lastTapAt in 1 until DOUBLE_TAP_WINDOW_MS
                    lastTapAt = if (isDoubleTap) 0L else now

                    if (isDoubleTap) {
                        if (scale > 1.01f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = DOUBLE_TAP_SCALE
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        StarImage(
            bytes = images[current],
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                },
            contentScale = ContentScale.Fit,
        )

        // 按钮是可靠出口——点背景关闭可能和缩放手势打架
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (onSave != null) {
                CircleIconButton(
                    glyph = StarIconGlyph.Export,
                    onClick = { onSave(current) },
                    size = 40.dp,
                    tint = Color.White,
                    containerColor = Color.White.copy(alpha = 0.18f),
                )
            }
            CircleIconButton(
                glyph = StarIconGlyph.Close,
                onClick = onDismiss,
                size = 40.dp,
                tint = Color.White,
                containerColor = Color.White.copy(alpha = 0.18f),
            )
        }

        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                images.forEachIndexed { index, bytes ->
                    val selected = index == current
                    Box(
                        modifier = Modifier
                            .size(if (selected) 52.dp else 42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = if (selected) 0.9f else 0.35f))
                            .clickable { current = index }
                            .padding(2.dp),
                    ) {
                        StarImage(
                            bytes = bytes,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
            }
        }
    }
}
