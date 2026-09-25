package io.github.feg55.zarp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

enum class PowerLook { Off, Busy, On }

/** Port of the desktop PowerButton: status ring, spinning arc while busy, soft glow when on. */
@Composable
fun PowerButton(look: PowerLook, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ringColor by animateColorAsState(
        when (look) {
            PowerLook.On -> Zc.Accent
            PowerLook.Busy -> Zc.Busy
            PowerLook.Off -> Zc.Off
        },
        label = "ring",
    )
    val iconColor by animateColorAsState(
        when (look) {
            PowerLook.On -> Zc.Accent
            PowerLook.Busy -> Zc.Busy
            PowerLook.Off -> Zc.TextDim
        },
        label = "icon",
    )
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1150, easing = LinearEasing), RepeatMode.Restart),
        label = "angle",
    )

    Canvas(
        modifier
            .clip(CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        val size = min(this.size.width, this.size.height) - 8f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val ring = size * 0.055f
        val unit = size / 200f // the desktop button is 200 px

        if (look == PowerLook.On) {
            for (i in 6 downTo 1) {
                drawCircle(
                    color = Zc.Accent.copy(alpha = 14f / 255f),
                    radius = size / 2f - ring + i * 1.5f * unit,
                    center = center,
                    style = Stroke(width = i * 2f * unit),
                )
            }
        }

        val ringRadius = size / 2f - ring / 2f
        drawCircle(
            color = ringColor.copy(alpha = if (look == PowerLook.Busy) 60f / 255f else 1f),
            radius = ringRadius,
            center = center,
            style = Stroke(width = ring),
        )
        if (look == PowerLook.Busy) {
            drawArc(
                color = Zc.Busy,
                startAngle = spin,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2, ringRadius * 2),
                style = Stroke(width = ring, cap = StrokeCap.Round),
            )
        }

        val discRadius = size / 2f - ring * 2.2f
        drawCircle(color = if (pressed) Zc.Border else Zc.Panel, radius = discRadius, center = center)

        val icon = discRadius * 2 * 0.36f
        val left = center.x - icon / 2f
        val top = center.y - icon / 2f + icon * 0.04f
        val stroke = Stroke(width = icon * 0.11f, cap = StrokeCap.Round)
        drawArc(
            color = iconColor, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(left, top), size = Size(icon, icon), style = stroke,
        )
        drawLine(
            color = iconColor,
            start = Offset(center.x, top - icon * 0.12f),
            end = Offset(center.x, top + icon * 0.42f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}
