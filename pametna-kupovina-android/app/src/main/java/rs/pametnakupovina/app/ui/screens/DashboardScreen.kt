package rs.pametnakupovina.app.ui.screens

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import rs.pametnakupovina.app.ui.components.ChoiceChip
import rs.pametnakupovina.app.ui.components.IconTile
import rs.pametnakupovina.app.ui.components.Meter
import rs.pametnakupovina.app.ui.components.Section
import rs.pametnakupovina.app.ui.components.SectionGap
import rs.pametnakupovina.app.ui.components.countUp
import rs.pametnakupovina.app.ui.components.enter
import rs.pametnakupovina.app.ui.components.pressScale
import rs.pametnakupovina.app.ui.components.rememberEntrance
import rs.pametnakupovina.app.ui.theme.LocalReduceMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import rs.pametnakupovina.app.ui.currency
import rs.pametnakupovina.app.ui.wholeAmount
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
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
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.listBottomPadding
import rs.pametnakupovina.app.ui.theme.LocalAppColors
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.GlassCard
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
    onOpenSales: () -> Unit = {},
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val listItemNames by dashboardViewModel.listItemNames.collectAsStateWithLifecycle()
    val familiesOnList by dashboardViewModel.familiesOnList.collectAsStateWithLifecycle()
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

    val entrance = rememberEntrance()
    Scaffold(containerColor = Color.Transparent, topBar = { AppTopBar(title = stringResource(R.string.dashboard_title)) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .testTag("dashboard-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = listBottomPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(SectionGap)
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
                    onChangeMonth = receiptViewModel::changeMonth,
                    modifier = Modifier.enter(entrance)
                )
            }
            item(key = "list-shortcut") {
                Section(stringResource(R.string.dashboard_active_list), Modifier.enter(entrance, 1)) {
                    ActiveListCard(listItemNames, onOpenList)
                    SalesShortcutCard(onOpenSales)
                }
            }
            item(key = "weekly-chart") {
                Section(stringResource(R.string.dashboard_weekly_title), Modifier.enter(entrance, 2)) {
                    WeeklyBarChart(weekBars(receiptState.selectedMonth, receiptState.byWeek), receiptState.selectedMonth)
                }
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
                        ).joinToString(" • "),
                        onList = habit.productFamilyId in familiesOnList,
                        onAdd = { dashboardViewModel.addHabitToList(habit) }
                    )
                } to resources.getString(R.string.dashboard_no_habits)
            }
            item(key = "analytics") {
                Section(stringResource(R.string.dashboard_analytics_title), Modifier.enter(entrance, 3)) {
                    AnalyticsCard(tab, onSelect = { tab = it }, rows, emptyText)
                }
            }
        }
    }
}

/**
 * Mesec, ukupna potrošnja i promena u odnosu na mesec pre; ispod linije tri
 * broja u istoj kartici. Sve je centrirano, a dugmad za mesec su u kartici.
 */
@Composable
private fun MonthSpendingCard(
    byMonth: List<MonthlySpendingDto>,
    selectedMonth: LocalDate,
    shops: Int,
    canGoForward: Boolean,
    onChangeMonth: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val month = byMonth.find { it.month == selectedMonth.toString() }
    val spent = month?.spent ?: 0.0
    val receipts = month?.receipts ?: 0
    val previousMonth = selectedMonth.minusMonths(1)
    val previous = byMonth.find { it.month == previousMonth.toString() }?.spent ?: 0.0
    val colors = LocalAppColors.current
    GlassCard(
        shape = MaterialTheme.shapes.large,
        color = colors.hero,
        contentColor = colors.onHero,
        modifier = modifier.fillMaxWidth()
    ) {
        Box {
            if (!colors.dark) {
                // Dijagonalni beli odsjaj preko plave kartice.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.22f), 0.38f to Color.Transparent))
                )
            }
            Column(
                modifier = Modifier.padding(start = AppSpacing.md, end = AppSpacing.md, top = AppSpacing.md, bottom = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeroButton(
                        description = stringResource(R.string.dashboard_previous_month),
                        onClick = { onChangeMonth(-1) },
                        modifier = Modifier
                            .rotate(180f)
                            .testTag("previous-month")
                    )
                    Text(
                        monthTitle(selectedMonth),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    HeroButton(
                        description = stringResource(R.string.dashboard_next_month),
                        onClick = { onChangeMonth(1) },
                        enabled = canGoForward,
                        modifier = Modifier.testTag("next-month")
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Text(
                        stringResource(R.string.dashboard_monthly_total),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.08.em),
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.alpha(0.85f)
                    )
                    // Broj se odbroji pri otvaranju i pri promeni meseca. Sa
                    // krupnim slovima se smanji umesto da pređe u drugi red.
                    val total = dinars(spent)
                    BasicText(
                        dinars(countUp(spent, selectedMonth)),
                        style = MaterialTheme.typography.displaySmall.copy(color = colors.onHero, textAlign = TextAlign.Center),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 44.sp),
                        modifier = Modifier.semantics { contentDescription = total.text }
                    )
                    // Samo kad oba meseca imaju račune: „-100%" za prazan mesec ne
                    // govori ništa.
                    if (spent > 0 && previous > 0) {
                        val change = ((spent - previous) / previous * 100).roundToInt()
                        Text(
                            // Pravi minus: crtica se kod cifara iste širine razmakne.
                            stringResource(
                                R.string.dashboard_change_vs_month,
                                if (change > 0) "+" else "−",
                                abs(change),
                                monthWord(previousMonth)
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .background(colors.heroPill, CircleShape)
                                .padding(horizontal = AppSpacing.md, vertical = 5.dp)
                        )
                    }
                }
                HorizontalDivider(color = colors.heroDivider)
                // Kad se „Prosečna korpa" prelomi, brojevi i dalje stoje u istoj visini.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                ) {
                    MonthStat(
                        stringResource(R.string.dashboard_stat_receipts),
                        countUp(receipts.toDouble(), selectedMonth).roundToInt().toString(),
                        Modifier.weight(1f)
                    )
                    VerticalDivider(color = colors.heroDivider)
                    MonthStat(
                        stringResource(R.string.dashboard_stat_shops),
                        countUp(shops.toDouble(), selectedMonth).roundToInt().toString(),
                        Modifier.weight(1f)
                    )
                    VerticalDivider(color = colors.heroDivider)
                    MonthStat(
                        stringResource(R.string.dashboard_stat_average),
                        if (receipts > 0) wholeAmount(countUp(spent / receipts, selectedMonth)) else "–",
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** Dugme za mesec u kartici potrošnje. */
@Composable
private fun HeroButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = LocalAppColors.current
    val source = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = colors.heroPill,
        contentColor = colors.onHero,
        interactionSource = source,
        modifier = modifier
            .pressScale(source)
            .alpha(if (enabled) 1f else 0.35f)
    ) {
        Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            AppIcon(R.drawable.ic_chevron_right, contentDescription = description)
        }
    }
}

@Composable
private fun MonthStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 6.dp, vertical = AppSpacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        // Svaka reč u svom redu; sa krupnim slovima se natpis smanji umesto
        // da se prelomi usred reči („Prodavni/ca").
        BasicText(
            label.replace(' ', '\n'),
            style = MaterialTheme.typography.labelMedium.copy(color = LocalContentColor.current, textAlign = TextAlign.Center),
            maxLines = label.count { it == ' ' } + 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
            modifier = Modifier.alpha(0.85f)
        )
    }
}

/** Iznos u celim jedinicama, sa valutom („RSD") sitnije, kao na nacrtu. */
@Composable
private fun dinars(value: Double) = buildAnnotatedString {
    append(wholeAmount(value))
    withStyle(MaterialTheme.typography.titleMedium.toSpanStyle()) { append(" " + currency()) }
}

/** Ceo red je dugme: ikonica, „Moj spisak", koliko stavki i prve dve, strelica. */
@Composable
private fun ActiveListCard(itemNames: List<String>, onOpenList: () -> Unit) {
    val summary = if (itemNames.isEmpty()) {
        stringResource(R.string.dashboard_list_empty)
    } else {
        val count = pluralStringResource(R.plurals.count_items, itemNames.size, itemNames.size)
        // Prva reč naziva je dovoljna da se spisak prepozna.
        val first = itemNames.take(2).joinToString(", ") { it.trim().substringBefore(' ') }
        val names = if (itemNames.size > 2) {
            stringResource(R.string.dashboard_list_more, first, itemNames.size - 2)
        } else {
            first
        }
        stringResource(R.string.dashboard_list_summary, count, names)
    }
    GlassCard(
        onClick = onOpenList,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open-list")
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconTile(R.drawable.ic_content_paste)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.dashboard_my_list), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon(R.drawable.ic_chevron_right, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

private val BarAreaHeight = 104.dp

/**
 * Bez chart biblioteke: stubić je deo fiksne visine. Stubići rastu odozdo i
 * menjaju visinu kad se promeni mesec; dodir na stubić pokazuje iznos.
 */
@Composable
private fun WeeklyBarChart(weeks: List<WeekBar>, month: LocalDate, modifier: Modifier = Modifier) {
    val maxSpent = (weeks.maxOfOrNull { it.spent } ?: 0.0).coerceAtLeast(1.0)
    val busiest = weeks.filter { it.spent > 0 }.maxByOrNull { it.spent }
    var picked by rememberSaveable(month.toString()) { mutableStateOf<Int?>(null) }
    val reduceMotion = LocalReduceMotion.current
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                weeks.forEachIndexed { index, week ->
                    val on = picked == index
                    val fraction = (week.spent / maxSpent).toFloat().coerceIn(0.08f, 1f)
                    val height = remember { Animatable(0f) }
                    LaunchedEffect(fraction) {
                        // Samo prvi put jedan za drugim; posle svi zajedno prate mesec.
                        if (height.value == 0f && !reduceMotion) delay(250L + index * 70L)
                        height.animateTo(fraction, spring(dampingRatio = 0.7f, stiffness = 180f))
                    }
                    val barColor by animateColorAsState(
                        if (on) MaterialTheme.colorScheme.primary else LocalAppColors.current.bar,
                        label = "bar"
                    )
                    val tipAlpha by animateFloatAsState(if (on) 1f else 0f, label = "barTip")
                    val amount = "${wholeAmount(week.spent)} ${currency()}"
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .selectable(selected = on, role = Role.Button) { picked = if (on) null else index }
                            .semantics { contentDescription = "${week.label}, $amount" }
                            .testTag("week-bar-$index"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            wholeAmount(week.spent),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.graphicsLayer {
                                alpha = tipAlpha
                                translationY = (1f - tipAlpha) * 6.dp.toPx()
                            }
                        )
                        Box(modifier = Modifier.height(BarAreaHeight), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(BarAreaHeight * height.value.coerceAtLeast(0f))
                                    .background(barColor, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                            )
                        }
                        Text(
                            week.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
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
    val share: Float? = null,
    /** Navika: dugme „Na spisak", kao u Akcijama. */
    val onAdd: (() -> Unit)? = null,
    val onList: Boolean = false
)

private fun share(part: Double, total: Double): Float? =
    if (total > 0) (part / total).toFloat() else null

/**
 * Prikazi se biraju čipovima koji skroluju vodoravno, pa sa krupnim slovima
 * nijedan naziv nije isečen.
 */
@Composable
private fun AnalyticsCard(
    selected: DashboardTab,
    onSelect: (DashboardTab) -> Unit,
    rows: List<DashboardRow>,
    emptyText: String
) {
    // Redovi ulaze jedan za drugim svaki put kad se promeni prikaz.
    val entrance = rememberEntrance(selected)
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = AppSpacing.lg)) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = AppSpacing.lg)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                DashboardTab.entries.forEach { tab ->
                    ChoiceChip(
                        text = stringResource(tab.label),
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.testTag(tab.tag)
                    )
                }
            }
            if (rows.isEmpty()) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AppSpacing.lg, end = AppSpacing.lg, top = AppSpacing.lg)
                )
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = AppSpacing.lg)
                    )
                }
                Column(
                    modifier = Modifier
                        .enter(entrance, index)
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
                        row.onAdd?.let { add ->
                            Spacer(Modifier.width(AppSpacing.md))
                            TonalActionButton(
                                text = stringResource(if (row.onList) R.string.sale_added else R.string.sale_add_to_list),
                                onClick = add,
                                enabled = !row.onList,
                                icon = if (row.onList) R.drawable.ic_check else R.drawable.ic_add,
                                primary = !row.onList
                            )
                        }
                        row.share?.let {
                            Text(
                                " (${(it * 100).roundToInt()}%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    row.share?.let { Meter(it) }
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
