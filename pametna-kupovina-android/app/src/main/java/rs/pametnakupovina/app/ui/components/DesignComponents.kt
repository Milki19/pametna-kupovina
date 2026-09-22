package rs.pametnakupovina.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.R

object AppSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

enum class StatusTone {
    POSITIVE,
    WARNING,
    ERROR,
    NEUTRAL
}

@Composable
private fun toneColors(tone: StatusTone): Pair<Color, Color> = when (tone) {
    StatusTone.POSITIVE ->
        MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
    StatusTone.WARNING ->
        MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
    StatusTone.ERROR ->
        MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
    StatusTone.NEUTRAL ->
        MaterialTheme.colorScheme.surfaceContainerHigh to
            MaterialTheme.colorScheme.onSurfaceVariant
}

@DrawableRes
private fun toneIcon(tone: StatusTone): Int = when (tone) {
    StatusTone.POSITIVE -> R.drawable.ic_check_circle
    StatusTone.WARNING, StatusTone.ERROR -> R.drawable.ic_warning
    StatusTone.NEUTRAL -> R.drawable.ic_info
}

@Composable
fun AppIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

@Composable
fun StatusPill(
    text: String,
    tone: StatusTone = StatusTone.NEUTRAL
) {
    val (container, content) = toneColors(tone)
    Surface(color = container, shape = CircleShape) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * One bar for every screen, with a real back arrow where there is somewhere
 * to go back to. The subtitle is for state the reader should notice, such as
 * working offline, and stays empty otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Column {
                // Na telefonu sa uvećanim pismom „Provera proizvoda" je bila
                // „Provera proiz…"; naslov ekrana sme da pređe u drugi red.
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        navigationIcon = {
            // Strelica gde ima kuda nazad, meni na ekranima do kojih se dolazi
            // iz menija; oba istovremeno bi značila da jedno od njih laže.
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    AppIcon(R.drawable.ic_arrow_back, contentDescription = "Nazad")
                }
            } else if (onMenu != null) {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.testTag("open-menu")
                ) {
                    AppIcon(R.drawable.ic_menu, contentDescription = "Meni")
                }
            }
        },
        actions = actions
    )
}

/**
 * The screen's main action, pinned where the thumb already rests. Lists and
 * messages scroll above it, so the button never moves under a finger that is
 * already on its way.
 */
@Composable
fun BottomActionBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                content = content
            )
        }
    }
}

@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = AppSpacing.xl, vertical = AppSpacing.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
    ) {
        icon?.let {
            AppIcon(it, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(AppSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * A message that belongs to the screen rather than to one item: a failed
 * sync, stale prices, an incomplete plan. The tone decides the colour and the
 * icon together, so warnings never rely on colour alone.
 */
@Composable
fun NoticeBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.NEUTRAL,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val (container, content) = toneColors(tone)
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                start = AppSpacing.lg,
                end = AppSpacing.sm,
                top = AppSpacing.md,
                bottom = if (actionLabel == null) AppSpacing.md else AppSpacing.xs
            ),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            AppIcon(
                toneIcon(tone),
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = AppSpacing.sm)
            ) {
                title?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(actionLabel, color = content)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
