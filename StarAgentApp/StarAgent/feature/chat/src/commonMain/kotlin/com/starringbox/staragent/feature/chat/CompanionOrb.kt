package com.starringbox.staragent.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import com.starringbox.staragent.core.data.CompanionPhase
import com.starringbox.staragent.core.designsystem.theme.avatarGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Drawn live: drifting light and layered fluid bands inside a softly breathing sphere. */
@Composable
internal fun CompanionOrb(
    modifier: Modifier = Modifier,
    phase: CompanionPhase = CompanionPhase.Idle,
    inputLevel: Float = 0f,
    accentIndex: Int = 1,
) {
    val transition = rememberInfiniteTransition(label = "companion-life")
    val time by transition.animateFloat(
        initialValue = 0f, targetValue = (PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "fluid-drift",
    )
    val colors = avatarGradient(accentIndex)
    Canvas(modifier) {
        val energy = when (phase) {
            CompanionPhase.Listening -> 0.018f + inputLevel * 0.07f
            CompanionPhase.Thinking, CompanionPhase.Transcribing -> 0.035f
            CompanionPhase.Speaking -> 0.04f
            else -> 0.012f
        }
        val pulse = 1f + sin(time * 4) * energy
        val radius = size.minDimension * 0.455f * pulse
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(
            Brush.radialGradient(listOf(colors.last().copy(alpha = 0.17f), Color.Transparent), center, radius * 1.14f),
            radius = radius * 1.14f, center = center,
        )
        val mask = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius), center + Offset(radius, radius)))
        }
        clipPath(mask) {
            val left = center.x - radius
            val top = center.y - radius
            val diameter = radius * 2
            drawCircle(
                Brush.verticalGradient(
                    listOf(colors.last(), colors.first(), Color(0xFFD7E4FF), Color(0xFFBCC9FB)),
                    startY = top, endY = top + diameter,
                ), radius, center,
            )
            repeat(9) { layer ->
                val path = Path()
                val baseline = 0.44f + layer * 0.042f
                val amplitude = 0.065f + energy * 1.6f
                for (step in 0..80) {
                    val x = step / 80f
                    // Different phase velocities make the light circulate without rotating the sphere.
                    val wave = sin(x * PI.toFloat() * 3 + time + layer * 0.35f) * amplitude +
                        cos(x * PI.toFloat() * 5 - time * 2 + layer * 0.5f) * 0.028f
                    val point = Offset(left + x * diameter, top + (baseline + wave) * diameter)
                    if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                path.lineTo(left + diameter, top + diameter)
                path.lineTo(left, top + diameter)
                path.close()
                drawPath(path, Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.105f), Color(0xFFCFD8FF).copy(alpha = 0.025f)),
                    startY = top + baseline * diameter, endY = top + diameter,
                ))
            }
            repeat(3) { index ->
                val light = Offset(
                    center.x + cos(time + index * 2.1f) * radius * 0.52f,
                    center.y + sin(time * 2 + index * 1.8f) * radius * 0.25f,
                )
                drawCircle(Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.06f), Color.Transparent),
                    light, radius * 0.75f,
                ), radius * 0.75f, light)
            }
        }
    }
}
