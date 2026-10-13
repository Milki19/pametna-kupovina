package rs.pametnakupovina.app.ui.components

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.graphics.BlurMaskFilter
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay
import rs.pametnakupovina.app.ui.theme.Ink
import rs.pametnakupovina.app.ui.theme.LocalAppColors
import rs.pametnakupovina.app.ui.theme.LocalReduceMotion

/** Koliko mesta dole zauzima lebdeća donja traka; nula na ekranima bez nje. */
val LocalBottomBarInset = compositionLocalOf { 0.dp }

private const val DRIFT_PERIOD_MS = 22_000f

/**
 * Pozadina iza stakla: tri meke mrlje boje koje polako plove, tamo-amo za oko
 * 22 s. Bez biblioteke za zamućenje: kartice su poluprovidne, a mekoću daju
 * sami gradijenti.
 */
@Composable
fun AppBackdrop(modifier: Modifier = Modifier) {
    val orbs = LocalAppColors.current.orbs
    var drift by remember { mutableFloatStateOf(0f) }
    if (!LocalReduceMotion.current) {
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            // ponytail: pomak je ispod piksela po koraku, pa je deset koraka u
            // sekundi dovoljno i ne drži ekran na punoj brzini osvežavanja.
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val start = System.currentTimeMillis()
                while (true) {
                    val phase = (System.currentTimeMillis() - start) / DRIFT_PERIOD_MS
                    drift = (1f - cos(phase * PI.toFloat())) / 2f
                    delay(100)
                }
            }
        }
    }
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = size.width * 0.06f * drift
                    translationY = -size.height * 0.05f * drift
                    rotationZ = 8f * drift
                }
        ) {
            val spots = listOf(Offset(0.1f, 0.08f), Offset(0.95f, 0.4f), Offset(0.25f, 0.95f))
            spots.forEachIndexed { index, spot ->
                val center = Offset(size.width * spot.x, size.height * spot.y)
                val radius = size.width * 0.75f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to orbs[index],
                        0.7f to orbs[index].copy(alpha = 0f),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
    }
}

/** Tanka ivica staklene kartice sa svetlim gornjim rubom. */
val cardBorder: BorderStroke
    @Composable get() {
        val colors = LocalAppColors.current
        return BorderStroke(
            1.dp,
            Brush.verticalGradient(listOf(colors.glassHigh, colors.glassEdge), startY = 0f, endY = 48f)
        )
    }

/**
 * Staklena kartica: poluprovidna površina preko pozadine, tanka ivica i, u
 * svetloj temi, meka senka. Sa [onClick] blago utone na dodir. [backdrop] se
 * crta ispod površine, unutar senke (npr. zamućenje sadržaja iza kartice).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = cardBorder,
    backdrop: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val lifted = if (!LocalAppColors.current.dark && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Modifier.softShadow(shape)
    } else {
        Modifier
    }
    if (onClick == null) {
        Surface(modifier = modifier.then(lifted).then(backdrop), shape = shape, color = color, contentColor = contentColor, border = border, content = content)
    } else {
        val source = remember { MutableInteractionSource() }
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.pressScale(source).then(lifted).then(backdrop),
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            interactionSource = source,
            content = content
        )
    }
}

/**
 * Meka senka samo oko kartice. Senka iz Material-a se crta i ispod površine,
 * pa kroz staklo prosijava kao tamniji okvir. Zamućenje oblika radi tek od
 * Androida 9.
 */
private fun Modifier.softShadow(shape: Shape): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val paint = Paint().apply {
        color = Ink.copy(alpha = 0.07f)
        asFrameworkPaint().maskFilter = BlurMaskFilter(16.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
    }
    val drop = 6.dp.toPx()
    onDrawBehind {
        clipPath(path, ClipOp.Difference) {
            drawIntoCanvas { canvas ->
                canvas.translate(0f, drop)
                canvas.drawPath(path, paint)
                canvas.translate(0f, -drop)
            }
        }
    }
}

/** Donji razmak liste: mesto za lebdeću traku na tabu, inače za sistemsku traku. */
@Composable
fun listBottomPadding(): Dp =
    maxOf(LocalBottomBarInset.current, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()) + AppSpacing.lg

/** Razmak između sekcija na ekranu. */
val SectionGap: Dp = 24.dp
