package com.starringbox.staragent.core.designsystem.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class StarIconGlyph {
    Chat,
    Task,
    Person,
    Group,
    Plus,
    Back,
    Search,
    More,
    Send,
    Mic,
    Camera,
    Check,
    Repeat,
    ChevronRight,
    ChevronDown,
    Sparkle,
    Close,
    Pause,
    Edit,
    Trash,
    Link,
    Bolt,
    Book,
    Export,
    Chip,
    Info,
}

/**
 * 自绘图标集。全部按 24×24 网格描述，避免引入图标库依赖，
 * 也让三端渲染完全一致。
 */
@Composable
fun StarIcon(
    icon: StarIconGlyph,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
    strokeWidth: Float = 1.75f,
) {
    Canvas(modifier.size(iconSize)) {
        val unit = size.minDimension / 24f
        val ox = (size.width - 24f * unit) / 2f
        val oy = (size.height - 24f * unit) / 2f
        val stroke = Stroke(width = strokeWidth * unit, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val sw = strokeWidth * unit

        fun p(x: Float, y: Float) = Offset(ox + x * unit, oy + y * unit)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(tint, p(x1, y1), p(x2, y2), sw, StrokeCap.Round)
        }

        fun circle(cx: Float, cy: Float, r: Float, filled: Boolean = false) {
            if (filled) drawCircle(tint, r * unit, p(cx, cy))
            else drawCircle(tint, r * unit, p(cx, cy), style = stroke)
        }

        fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, filled: Boolean = false) {
            val topLeft = p(l, t)
            val sz = Size((r - l) * unit, (b - t) * unit)
            val cr = CornerRadius(radius * unit, radius * unit)
            if (filled) drawRoundRect(tint, topLeft, sz, cr)
            else drawRoundRect(tint, topLeft, sz, cr, style = stroke)
        }

        fun poly(points: List<Pair<Float, Float>>, close: Boolean = false, filled: Boolean = false) {
            val path = Path()
            points.forEachIndexed { i, point ->
                val x = ox + point.first * unit
                val y = oy + point.second * unit
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (close) path.close()
            if (filled) drawPath(path, tint) else drawPath(path, tint, style = stroke)
        }

        fun arc(l: Float, t: Float, r: Float, b: Float, start: Float, sweep: Float) {
            drawArc(
                color = tint,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = p(l, t),
                size = Size((r - l) * unit, (b - t) * unit),
                style = stroke,
            )
        }

        when (icon) {
            StarIconGlyph.Chat -> {
                roundRect(3f, 4.5f, 21f, 17f, 4.5f)
                poly(listOf(7.5f to 17f, 7.5f to 20.8f, 11.4f to 17f))
            }

            StarIconGlyph.Task -> {
                circle(12f, 12f, 8f)
                line(12f, 12f, 12f, 7.2f)
                line(12f, 12f, 15.8f, 13.8f)
            }

            StarIconGlyph.Person -> {
                circle(12f, 8.5f, 3.6f)
                arc(4.5f, 13.5f, 19.5f, 25.5f, 190f, 160f)
            }

            StarIconGlyph.Group -> {
                // 后面那个人：画小一点、偏右上，是"这里不止一个人"的那一层
                circle(16.6f, 8f, 2.9f)
                arc(12.6f, 13f, 22f, 20.6f, 205f, 130f)

                // 前面那个人
                circle(9.2f, 9.4f, 3.4f)
                arc(2.8f, 14.6f, 15.6f, 25f, 195f, 150f)
            }

            StarIconGlyph.Plus -> {
                line(12f, 5f, 12f, 19f)
                line(5f, 12f, 19f, 12f)
            }

            StarIconGlyph.Back -> poly(listOf(15f to 5f, 8f to 12f, 15f to 19f))

            StarIconGlyph.Search -> {
                circle(10.5f, 10.5f, 6.2f)
                line(15.2f, 15.2f, 19.5f, 19.5f)
            }

            StarIconGlyph.More -> {
                circle(5.5f, 12f, 1.7f, filled = true)
                circle(12f, 12f, 1.7f, filled = true)
                circle(18.5f, 12f, 1.7f, filled = true)
            }

            StarIconGlyph.Send -> {
                line(12f, 19.5f, 12f, 5f)
                poly(listOf(6f to 11f, 12f to 5f, 18f to 11f))
            }

            StarIconGlyph.Mic -> {
                roundRect(9f, 3f, 15f, 13.5f, 3f)
                arc(5.5f, 8f, 18.5f, 19f, 0f, 180f)
                line(12f, 19f, 12f, 21.5f)
            }

            StarIconGlyph.Camera -> {
                roundRect(3f, 6.5f, 21f, 19.5f, 3.2f)
                circle(12f, 13f, 3.4f)
                poly(listOf(8.5f to 6.5f, 10f to 4f, 14f to 4f, 15.5f to 6.5f))
            }

            StarIconGlyph.Check -> poly(listOf(5f to 12.5f, 10f to 17.5f, 19f to 7f))

            StarIconGlyph.Repeat -> {
                arc(4f, 4f, 20f, 20f, 300f, 270f)
                poly(
                    listOf(6.67f to 5.23f, 7.06f to 9.15f, 3.08f to 6.85f),
                    close = true,
                    filled = true,
                )
            }

            StarIconGlyph.ChevronRight -> poly(listOf(9.5f to 5.5f, 16f to 12f, 9.5f to 18.5f))

            StarIconGlyph.ChevronDown -> poly(listOf(5.5f to 9.5f, 12f to 16f, 18.5f to 9.5f))

            StarIconGlyph.Sparkle -> poly(
                listOf(
                    12f to 2f, 14.4f to 9.6f, 22f to 12f, 14.4f to 14.4f,
                    12f to 22f, 9.6f to 14.4f, 2f to 12f, 9.6f to 9.6f,
                ),
                close = true,
                filled = true,
            )

            StarIconGlyph.Close -> {
                line(6f, 6f, 18f, 18f)
                line(18f, 6f, 6f, 18f)
            }

            StarIconGlyph.Pause -> {
                roundRect(8f, 6f, 10.5f, 18f, 1f, filled = true)
                roundRect(13.5f, 6f, 16f, 18f, 1f, filled = true)
            }

            StarIconGlyph.Edit -> {
                line(5f, 19f, 16.5f, 7.5f)
                line(14.5f, 5.5f, 18.5f, 9.5f)
                line(5f, 19f, 9.5f, 19f)
            }

            StarIconGlyph.Trash -> {
                roundRect(5.5f, 7f, 18.5f, 20f, 2.5f)
                line(3.5f, 7f, 20.5f, 7f)
                poly(listOf(9.5f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 14.5f to 7f))
                line(10.5f, 11f, 10.5f, 16f)
                line(13.5f, 11f, 13.5f, 16f)
            }

            StarIconGlyph.Link -> {
                circle(9.2f, 12f, 5.6f)
                circle(14.8f, 12f, 5.6f)
            }

            StarIconGlyph.Bolt -> poly(
                listOf(
                    13f to 2.5f, 5.5f to 13.5f, 11f to 13.5f,
                    10.5f to 21.5f, 18.5f to 10.5f, 13f to 10.5f,
                ),
                close = true,
                filled = true,
            )

            StarIconGlyph.Book -> {
                roundRect(4f, 4f, 20f, 20f, 2.5f)
                line(8.5f, 4f, 8.5f, 20f)
                line(11.5f, 8.5f, 17f, 8.5f)
                line(11.5f, 12f, 17f, 12f)
            }

            StarIconGlyph.Export -> {
                poly(listOf(6f to 14f, 6f to 20f, 18f to 20f, 18f to 14f))
                line(12f, 15f, 12f, 3.5f)
                poly(listOf(7.5f to 8f, 12f to 3.5f, 16.5f to 8f))
            }

            StarIconGlyph.Chip -> {
                roundRect(6f, 6f, 18f, 18f, 2.5f)
                roundRect(10f, 10f, 14f, 14f, 1f, filled = true)
                line(9f, 6f, 9f, 3f)
                line(15f, 6f, 15f, 3f)
                line(9f, 18f, 9f, 21f)
                line(15f, 18f, 15f, 21f)
                line(6f, 9f, 3f, 9f)
                line(6f, 15f, 3f, 15f)
                line(18f, 9f, 21f, 9f)
                line(18f, 15f, 21f, 15f)
            }

            StarIconGlyph.Info -> {
                circle(12f, 12f, 8.5f)
                line(12f, 11f, 12f, 16.5f)
                circle(12f, 7.8f, 0.9f, filled = true)
            }
        }
    }
}
