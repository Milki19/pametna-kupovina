package rs.pametnakupovina.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.shortDate

/**
 * Koliki je popust, bojom iste palete kao ostatak aplikacije: nana za mali,
 * ćilibar za srednji, crvena za veliki. Broj stoji na oznaci, pa boja nikad
 * nije jedini znak.
 */
enum class DiscountTier { SMALL, MEDIUM, LARGE }

/**
 * Ceo procenat popusta, kao na serveru: 266,99 → 199,99 je 25 %. Null kad
 * nema niže cene ili je razlika manja od pola procenta.
 */
fun discountPercent(regularPrice: Double?, price: Double?): Int? {
    if (regularPrice == null || price == null || regularPrice <= 0.0 || price >= regularPrice) return null
    val percent = Math.round((1.0 - price / regularPrice) * 100.0).toInt()
    return percent.takeIf { it >= 1 }
}

fun discountTier(percent: Int): DiscountTier = when {
    percent >= 40 -> DiscountTier.LARGE
    percent >= 20 -> DiscountTier.MEDIUM
    else -> DiscountTier.SMALL
}

/** Pozadina i slova oznake popusta, u svetloj i tamnoj temi. */
@Composable
fun discountColors(percent: Int): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (discountTier(percent)) {
        DiscountTier.SMALL -> scheme.secondaryContainer to scheme.onSecondaryContainer
        DiscountTier.MEDIUM -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DiscountTier.LARGE -> scheme.errorContainer to scheme.onErrorContainer
    }
}

/**
 * Akcijska cena kao tekst na beloj kartici: tamnija nijansa iste boje, da
 * se čita i na svetloj i na tamnoj pozadini.
 */
@Composable
fun saleTextColor(percent: Int): Color {
    val scheme = MaterialTheme.colorScheme
    return when (discountTier(percent)) {
        DiscountTier.SMALL -> scheme.secondary
        DiscountTier.MEDIUM -> scheme.tertiary
        DiscountTier.LARGE -> scheme.error
    }
}

/** „−25 %" na obojenoj pilulici. */
@Composable
fun DiscountBadge(
    percent: Int,
    modifier: Modifier = Modifier,
    large: Boolean = false
) {
    val (container, content) = discountColors(percent)
    val description = stringResource(R.string.sale_discount_description, percent)
    Surface(
        color = container,
        contentColor = content,
        shape = CircleShape,
        modifier = modifier
            .semantics { contentDescription = description }
            .testTag("discount-badge")
    ) {
        Text(
            stringResource(R.string.sale_discount_badge, percent),
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(
                horizontal = if (large) 12.dp else 8.dp,
                vertical = if (large) 4.dp else 2.dp
            )
        )
    }
}

/** Redovna cena, precrtana, uz akcijsku. */
@Composable
fun StruckPrice(value: Double, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.sale_regular_price, money(value))
    Text(
        money(value),
        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.semantics { contentDescription = description }
    )
}

/**
 * Red ispod cene lanca kad je ta cena akcija: popust, redovna cena i do
 * kada važi. Sa krupnim slovima prelazi u drugi red umesto da se seče.
 */
@Composable
fun SaleLine(
    discountPercent: Int,
    regularPrice: Double?,
    saleEndDate: String?,
    modifier: Modifier = Modifier
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        DiscountBadge(discountPercent)
        regularPrice?.let { StruckPrice(it) }
        saleEndDate?.let {
            Text(
                stringResource(R.string.sale_until, shortDate(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
