package com.actme.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MarqueeBorder(
    isActive: Boolean,
    modifier: Modifier = Modifier,
    glowWidth: Dp = 30.dp,
    cornerRadius: Dp = 36.dp,
    content: @Composable () -> Unit
) {
    if (!isActive) {
        Box(modifier = modifier) { content() }
        return
    }

    val infiniteTransition = rememberInfiniteTransition()

    // Breathing: overall opacity pulses
    val breath by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    // Color sweep rotation
    val sweepPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(5500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()

                val gw = glowWidth.toPx()
                val baseCr = cornerRadius.toPx()
                val center = Offset(size.width / 2, size.height / 2)
                val numStrokes = 14
                val strokeWidth = gw / numStrokes

                // Sweep gradient with shifted stops for rotation
                val stops = listOf(
                    0.00f to LogoBlue200,
                    0.10f to LogoBlue300,
                    0.22f to LogoBlue400,
                    0.32f to LogoBlue500,
                    0.40f to LogoCyan,
                    0.48f to LogoBlue500,
                    0.58f to LogoBlue400,
                    0.70f to LogoBlue300,
                    0.82f to LogoBlue200,
                    0.90f to LogoBlue100,
                    0.96f to LogoBlue50,
                    1.00f to LogoBlue200
                )

                val shifted = stops.map { (pos, color) ->
                    ((pos + sweepPhase) % 1f) to color
                }.sortedBy { it.first }.toTypedArray()

                val sweep = Brush.sweepGradient(colorStops = shifted, center = center)

                // Concentric rounded-rect strokes fading from edge inward
                for (i in 0 until numStrokes) {
                    val t = i.toFloat() / numStrokes
                    val fade = (1f - t) * (1f - t)
                    val alpha = fade * breath
                    val inset = t * gw
                    val cr = (baseCr - inset).coerceAtLeast(0f)

                    drawRoundRect(
                        brush = sweep,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - inset * 2, size.height - inset * 2),
                        cornerRadius = CornerRadius(cr),
                        style = Stroke(width = strokeWidth),
                        alpha = alpha
                    )
                }
            }
    ) {
        content()
    }
}
