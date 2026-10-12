package rs.pametnakupovina.app.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import rs.pametnakupovina.app.ui.theme.LocalReduceMotion

/** Dugme ili kartica na dodir blago utone i vrati se sa oprugom. */
@Composable
fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
        label = "press"
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Otvaranje ekrana: kartice ulaze odozdo jedna za drugom. Važi samo prvih
 * pola sekunde, pa redovi koji se pojave kasnije (skrolovanje) ne trepću.
 */
class Entrance internal constructor(private val startedAt: Long, private val enabled: Boolean) {
    internal val open: Boolean get() = enabled && SystemClock.uptimeMillis() - startedAt < 500
}

@Composable
fun rememberEntrance(key: Any? = Unit): Entrance {
    val reduceMotion = LocalReduceMotion.current
    return remember(key) { Entrance(SystemClock.uptimeMillis(), !reduceMotion) }
}

@Composable
fun Modifier.enter(entrance: Entrance, index: Int = 0): Modifier {
    val animate = remember(entrance) { entrance.open }
    val progress = remember(entrance) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(entrance) {
        if (animate) {
            delay(index.coerceAtMost(8) * 60L)
            progress.animateTo(1f, tween(450, easing = EaseOutCubic))
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 16.dp.toPx()
    }
}

/** Broj koji se odbroji od nule kad se pojavi i kad se promeni [key]. */
@Composable
fun countUp(target: Double, key: Any? = Unit): Double {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(key, target) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(900, easing = EaseOutCubic))
    }
    return target * progress.value
}

/** Traka udela ili napretka; puni se s leva. */
@Composable
fun Meter(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val target = fraction.coerceIn(0f, 1f)
    val shown = remember { Animatable(0f) }
    LaunchedEffect(target) { shown.animateTo(target, tween(700, easing = EaseOutCubic)) }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .progressSemantics(target)
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(color, size = Size(size.width * shown.value, size.height), cornerRadius = radius)
    }
}

/** Kartica koja trepće dok se čeka sadržaj koji će stati na njeno mesto. */
@Composable
fun ShimmerCard(modifier: Modifier = Modifier) {
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmerShift"
    )
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind {
                val start = size.width * (shift * 3f - 1.5f)
                drawRect(
                    Brush.linearGradient(
                        listOf(base, highlight, base),
                        start = Offset(start, 0f),
                        end = Offset(start + size.width, size.height)
                    )
                )
            }
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    )
}
