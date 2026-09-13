package rs.pametnakupovina.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Large enough to hit reliably with an unsteady hand or in a coat pocket. */
val MinimumTouchTarget = 52.dp

/**
 * Waiting looks like the list that is coming, not like a spinning circle that
 * says nothing about it. The placeholder rows keep the layout still, so
 * arriving content does not jump under a finger already reaching for it.
 */
@Composable
fun LoadingState(message: String = "Učitavanje…") {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg)
            .semantics { contentDescription = message },
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        repeat(5) { index ->
            SkeletonRow(widthFraction = if (index % 2 == 0) 1f else 0.7f)
        }
    }
}

@Composable
fun SkeletonRow(widthFraction: Float = 1f) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )

    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SkeletonBlock(heightDp = 22, widthFraction = widthFraction, alpha = alpha)
        SkeletonBlock(heightDp = 16, widthFraction = widthFraction * 0.5f, alpha = alpha)
    }
}

@Composable
private fun SkeletonBlock(
    heightDp: Int,
    widthFraction: Float,
    alpha: Float
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(heightDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AppSpacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(AppSpacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(AppSpacing.lg))
        Button(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = MinimumTouchTarget)
        ) {
            Text("Pokušaj ponovo")
        }
    }
}
