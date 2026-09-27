package rs.pametnakupovina.app.ui.screens

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.currency
import rs.pametnakupovina.app.ui.wholeAmount
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.MonthlySpendingDto
import rs.pametnakupovina.app.data.network.WeeklySpendingDto
import rs.pametnakupovina.app.data.market.CurrentMarket
import rs.pametnakupovina.app.ui.DashboardViewModel
import rs.pametnakupovina.app.ui.ReceiptViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.cardBorder
import rs.pametnakupovina.app.ui.components.heroColors
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.dateTime
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.monthName
import rs.pametnakupovina.app.ui.monthWord

private enum class DashboardTab(@param:StringRes val label: Int, val tag: String) {
    RECEIPTS(R.string.dashboard_tab_receipts, "tab-receipts"),
    SHOPS(R.string.dashboard_tab_shops, "tab-shops"),
    CATEGORIES(R.string.dashboard_tab_categories, "tab-categories"),
    HABITS(R.string.dashboard_tab_habits, "tab-habits")
}

/**
 * Početni ekran: potrošnja za izabrani mesec (kao mani.rs) i, odmah ispod,
 * brz put do spiska — spisak ostaje srž aplikacije, dashboard ne sme da mu
 * stane na put.
 */
@Composable
fun DashboardScreen(
    onOpenList: () -> Unit,
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val listItemCount by dashboardViewModel.listItemCount.collectAsStateWithLifecycle()
    val receiptState by receiptViewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DashboardTab.RECEIPTS) }
    // Redovi se slažu van kompozicije (u LazyColumn bloku), pa tekst čitaju odavde.
    val resources = LocalResources.current

    val monthReceipts = remember(receiptState.receipts, receiptState.selectedMonth) {
        receiptState.receipts.filter { isInMonth(it.issuedAt, receiptState.selectedMonth) }
    }
    val canGoForward = receiptState.selectedMonth.isBefore(
        LocalDate.now(CurrentMarket.settings.zone).withDayOfMonth(1)
    )

    Scaffold(topBar = { AppTopBar(title = stringResource(R.string.dashboard_title)) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("dashboard-list"),
            contentPadding = PaddingValues(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
        ) {
            // Skeniranje iz menija javlja ishod ovde; bez ovoga su i uspeh i
            // greška prolazili ćutke.
            val receiptNotice = if (receiptState.scanning) uiText(R.string.receipt_scanning) else receiptState.message
            receiptNotice?.let { text ->
                item(key = "receipt-notice") {
                    NoticeBanner(
                        text = text.asString(),
                        actionLabel = stringResource(R.string.dashboard_ok).takeUnless { receiptState.scanning },
                        onAction = receiptViewModel::dismissMessage.takeUnless { receiptState.scanning },
                        modifier = Modifier.testTag("receipt-notice")
                    )
                }
            }
            item(key = "spending") {
                MonthSpendingCard(
                    byMonth = receiptState.byMonth,
                    selectedMonth = receiptState.selectedMonth,
                    shops = monthReceipts.distinctBy { it.shopName }.size,
                    canGoForward = canGoForward,
                    onChangeMonth = receiptViewModel::changeMonth
                )
            }
            item(key = "list-shortcut") {
                ActiveListCard(listItemCount, onOpenList)
            }
            item(key = "weekly-chart") {
                WeeklyBarChart(weekBars(receiptState.selectedMonth, receiptState.byWeek))
            }
            val (rows, emptyText) = when (tab) {
                DashboardTab.RECEIPTS -> monthReceipts.map {
                    DashboardRow(it.shopName, receiptTimestamp(it.issuedAt, resources), money(it.totalAmount))
                } to resources.getString(R.string.dashboard_no_receipts)

                DashboardTab.SHOPS -> {
                    val total = receiptState.byShop.sumOf { it.spent }
                    receiptState.byShop.map {
                        DashboardRow(
                            it.shopName,
                            resources.getQuantityString(R.plurals.dashboard_receipt_count, it.receipts, it.receipts),
                            "${wholeAmount(it.spent)} ${currency()}",
                            share = share(it.spent, total)
                        )
                    } to resources.getString(R.string.dashboard_no_shops)
                }

                DashboardTab.CATEGORIES -> {
                    val total = receiptState.byCategory.sumOf { it.spent }
                    receiptState.byCategory.map {
                        DashboardRow(it.category, trailing = "${wholeAmount(it.spent)} ${currency()}", share = share(it.spent, total))
                    } to resources.getString(R.string.dashboard_no_receipts)
                }

                // Navike su za sve vreme, ne za izabrani mesec: to je ono što
                // kupac obično kupuje, a ne šta je kupio u junu.
                DashboardTab.HABITS -> receiptState.habits.map { habit ->
                    DashboardRow(
                        habit.name,
                        listOfNotNull(
                            resources.getQuantityString(R.plurals.dashboard_times_count, habit.times, habit.times),
                            marketDay(habit.lastBought)?.let {
                                resources.getString(R.string.dashboard_last_bought, date(it.toString()))
                            }
                        ).joinToString(" • ")
                    )
                } to resources.getString(R.string.dashboard_no_habits)
            }
            item(key = "analytics") {
                AnalyticsCard(tab, onSelect = { tab = it }, rows, emptyText)
            }
        }
    }
}

/** Mesec, ukupna potrošnja i promena u odnosu na mesec pre. */
@Composable
private fun MonthSpendingCard(
    byMonth: List<MonthlySpendingDto>,
    selectedMonth: LocalDate,
    shops: Int,
    canGoForward: Boolean,
    onChangeMonth: (Int) -> Unit
) {
    val month = byMonth.find { it.month == selectedMonth.toString() }
    val spent = month?.spent ?: 0.0
    val receipts = month?.receipts ?: 0
    val previousMonth = selectedMonth.minusMonths(1)
    val previous = byMonth.find { it.month == previousMonth.toString() }?.spent ?: 0.0
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { onChangeMonth(-1) },
                    modifier = Modifier.testTag("previous-month")
                ) {
                    AppIcon(
                        R.drawable.ic_chevron_right,
                        contentDescription = stringResource(R.string.dashboard_previous_month),
                        modifier = Modifier.rotate(180f)
                    )
                }
                Text(
                    monthTitle(selectedMonth),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = { onChangeMonth(1) },
                    enabled = canGoForward,
                    modifier = Modifier.testTag("next-month")
                ) {
                    AppIcon(R.drawable.ic_chevron_right, contentDescription = stringResource(R.string.dashboard_next_month))
                }
            }
            Text(
                stringResource(R.string.dashboard_monthly_total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(dinars(spent), style = MaterialTheme.typography.displaySmall)
            // Samo kad oba meseca imaju račune: „-100%" za prazan mesec ne
            // govori ništa.
            if (spent > 0 && previous > 0) {
                val change = ((spent - previous) / previous * 100).roundToInt()
                StatusPill(
                    // Pravi minus: crtica se kod cifara iste širine razmakne.
                    stringResource(
                        R.string.dashboard_change_vs_month,
                        if (change > 0) "+" else "−",
                        abs(change),
                        monthWord(previousMonth)
                    ),
                    if (change > 0) StatusTone.WARNING else StatusTone.POSITIVE
                )
            }
            // Kad se „Prosečna korpa" prelomi, brojevi i dalje stoje u istoj visini.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = AppSpacing.sm)
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.shapes.small
                    )
                    .padding(vertical = AppSpacing.md)
            ) {
                MonthStat(stringResource(R.string.dashboard_stat_receipts), receipts.toString(), Modifier.weight(1f))
                MonthStat(stringResource(R.string.dashboard_stat_shops), shops.toString(), Modifier.weight(1f))
                MonthStat(
                    stringResource(R.string.dashboard_stat_average),
                    if (receipts > 0) wholeAmount(spent / receipts) else "–",
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MonthStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Text(value, style = MaterialTheme.typography.titleLarge)
    }
}

/** Iznos u celim jedinicama, sa valutom („RSD") sitnije, kao na nacrtu. */
@Composable
private fun dinars(value: Double) = buildAnnotatedString {
    append(wholeAmount(value))
    withStyle(MaterialTheme.typography.titleMedium.toSpanStyle()) { append(" " + currency()) }
}

@Composable
private fun ActiveListCard(itemCount: Int, onOpenList: () -> Unit) {
    val hero = heroColors()
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = hero.container,
        contentColor = hero.content,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(stringResource(R.string.dashboard_active_list), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.dashboard_my_list), style = MaterialTheme.typography.headlineSmall)
            Surface(
                onClick = onOpenList,
                shape = RoundedCornerShape(14.dp),
                color = hero.action,
                contentColor = hero.onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.xs)
                    .testTag("open-list")
            ) {
                Row(
                    modifier = Modifier.padding(AppSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppIcon(R.drawable.ic_content_paste, contentDescription = null)
                    Spacer(Modifier.width(AppSpacing.md))
                    Text(
                        if (itemCount == 0) {
                            stringResource(R.string.dashboard_list_empty)
                        } else {
                            stringResource(
                                R.string.dashboard_open_list,
                                pluralStringResource(R.plurals.count_items, itemCount, itemCount)
                            )
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    AppIcon(R.drawable.ic_chevron_right, contentDescription = null)
                }
            }
        }
    }
}

private data class WeekBar(val label: String, val spent: Double)

private fun weekBars(month: LocalDate, byWeek: List<WeeklySpendingDto>): List<WeekBar> {
    val lengthOfMonth = YearMonth.from(month).lengthOfMonth()
    val bucketCount = if (lengthOfMonth >= 29) 5 else 4
    val byBucket = byWeek.associateBy { it.bucket }
    return (0 until bucketCount).map { bucket ->
        val startDay = bucket * 7 + 1
        val endDay = minOf(startDay + 6, lengthOfMonth)
        WeekBar(
            label = "$startDay–$endDay",
            spent = byBucket[bucket]?.spent ?: 0.0
        )
    }
}

/**
 * Bez chart biblioteke: visina stuba je udeo fiksne visine kolone
 * (Spacer + stub, oba sa weight-om, uvek zbir 1), kao na mani.rs. Najveća
 * nedelja je tamna, ostale svetle.
 */
@Composable
private fun WeeklyBarChart(weeks: List<WeekBar>, modifier: Modifier = Modifier) {
    val maxSpent = (weeks.maxOfOrNull { it.spent } ?: 0.0).coerceAtLeast(1.0)
    val busiest = weeks.filter { it.spent > 0 }.maxByOrNull { it.spent }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Text(stringResource(R.string.dashboard_weekly_title), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                weeks.forEach { week ->
                    val fraction = (week.spent / maxSpent).toFloat().coerceIn(0f, 1f) * 0.85f
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.01f)))
                        if (week.spent > 0) {
                            Text(
                                wholeAmount(week.spent),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .weight(fraction.coerceAtLeast(0.02f))
                                .background(
                                    color = if (week == busiest) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                )
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                weeks.forEach { week ->
                    Text(
                        week.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (week == busiest) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            busiest?.let {
                Text(
                    stringResource(
                        R.string.dashboard_busiest_week,
                        wholeAmount(it.spent),
                        wholeAmount(weeks.sumOf { w -> w.spent } / weeks.size),
                        currency()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class DashboardRow(
    val title: String,
    val subtitle: String? = null,
    val trailing: String? = null,
    /** Udeo u ukupnom, za traku ispod reda. */
    val share: Float? = null
)

private fun share(part: Double, total: Double): Float? =
    if (total > 0) (part / total).toFloat() else null

@Composable
private fun AnalyticsCard(
    selected: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
    rows: List<DashboardRow>,
    emptyText: String
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Text(stringResource(R.string.dashboard_analytics_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(AppSpacing.md))
            PillTabs(selected, onSelect)
            if (rows.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppSpacing.lg)
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0 && row.share == null) HorizontalDivider()
                Column(
                    modifier = Modifier.padding(vertical = AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge)
                            row.subtitle?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        row.trailing?.let {
                            Spacer(Modifier.width(AppSpacing.md))
                            Text(it, style = MaterialTheme.typography.titleMedium)
                        }
                        row.share?.let {
                            Text(
                                " (${(it * 100).roundToInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    row.share?.let {
                        LinearProgressIndicator(
                            progress = { it },
                            drawStopIndicator = {},
                            gapSize = 0.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tabovi kao pilule na svetloj podlozi, izabrani beo. Kad sva četiri naziva
 * ne staju u jedan red (krupna slova, uvećan ekran), ostaje samo izabrani, sa
 * strelicama levo i desno — isečeni „Prodav" i „Katego" su bili ružni.
 */
@Composable
private fun PillTabs(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    val tabs = DashboardTab.entries
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labels = tabs.map { stringResource(it.label) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
            .padding(AppSpacing.xs)
    ) {
        val labelRoom = with(density) { (maxWidth / tabs.size - AppSpacing.xs).roundToPx() }
        val fits = labels.all { measurer.measure(it, style).size.width <= labelRoom }
        if (fits) {
            Row {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    Surface(
                        onClick = { onSelect(tab) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.surfaceContainerLowest else Color.Transparent,
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shadowElevation = if (isSelected) 1.dp else 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .testTag(tab.tag)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(labels[tab.ordinal], style = style, maxLines = 1)
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSelect(tabs[selected.ordinal - 1]) },
                    enabled = selected.ordinal > 0
                ) {
                    AppIcon(
                        R.drawable.ic_chevron_right,
                        contentDescription = stringResource(R.string.dashboard_previous_view),
                        modifier = Modifier.rotate(180f)
                    )
                }
                Text(
                    labels[selected.ordinal],
                    style = style,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(selected.tag)
                )
                IconButton(
                    onClick = { onSelect(tabs[selected.ordinal + 1]) },
                    enabled = selected.ordinal < tabs.lastIndex
                ) {
                    AppIcon(R.drawable.ic_chevron_right, contentDescription = stringResource(R.string.dashboard_next_view))
                }
            }
        }
    }
}

@Composable
private fun monthTitle(month: LocalDate): String =
    monthName(month).replaceFirstChar(Char::uppercase)

private fun marketDay(instantIso: String): LocalDate? = try {
    // The receipt's day where the market is, as the server counts months.
    Instant.parse(instantIso).atZone(CurrentMarket.settings.zone).toLocalDate()
} catch (_: Exception) {
    null
}

private fun isInMonth(issuedAtIso: String, month: LocalDate): Boolean =
    marketDay(issuedAtIso)?.let { it.year == month.year && it.month == month.month } ?: false

private fun receiptTimestamp(issuedAtIso: String, resources: Resources): String = try {
    dateTime(Instant.parse(issuedAtIso).toEpochMilli(), resources)
} catch (_: Exception) {
    issuedAtIso
}
