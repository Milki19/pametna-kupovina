package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import java.time.ZoneId
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.MonthlySpendingDto
import rs.pametnakupovina.app.data.network.WeeklySpendingDto
import rs.pametnakupovina.app.ui.DashboardViewModel
import rs.pametnakupovina.app.ui.ReceiptViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.counted
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.dateTime
import rs.pametnakupovina.app.ui.items
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.monthName

private val BELGRADE = ZoneId.of("Europe/Belgrade")

private enum class DashboardTab(val label: String, val tag: String) {
    RECEIPTS("Računi", "tab-receipts"),
    SHOPS("Prodavnice", "tab-shops"),
    CATEGORIES("Kategorije", "tab-categories"),
    HABITS("Navike", "tab-habits")
}

/**
 * Početni ekran: potrošnja za izabrani mesec (kao mani.rs) i, odmah ispod,
 * brz put do spiska — spisak ostaje srž aplikacije, dashboard ne sme da mu
 * stane na put.
 */
@Composable
fun DashboardScreen(
    onOpenMenu: () -> Unit,
    onOpenList: () -> Unit,
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
    receiptViewModel: ReceiptViewModel = hiltViewModel()
) {
    val listItemCount by dashboardViewModel.listItemCount.collectAsStateWithLifecycle()
    val receiptState by receiptViewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(DashboardTab.RECEIPTS) }

    val monthReceipts = remember(receiptState.receipts, receiptState.selectedMonth) {
        receiptState.receipts.filter { isInMonth(it.issuedAt, receiptState.selectedMonth) }
    }
    val canGoForward = receiptState.selectedMonth.isBefore(LocalDate.now().withDayOfMonth(1))

    Scaffold(
        topBar = {
            AppTopBar(
                title = monthTitle(receiptState.selectedMonth),
                onMenu = onOpenMenu,
                actions = {
                    IconButton(
                        onClick = { receiptViewModel.changeMonth(-1) },
                        modifier = Modifier.testTag("previous-month")
                    ) {
                        AppIcon(R.drawable.ic_arrow_back, contentDescription = "Prethodni mesec")
                    }
                    IconButton(
                        onClick = { receiptViewModel.changeMonth(1) },
                        enabled = canGoForward,
                        modifier = Modifier.testTag("next-month")
                    ) {
                        AppIcon(R.drawable.ic_chevron_right, contentDescription = "Sledeći mesec")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("dashboard-list"),
            contentPadding = PaddingValues(
                horizontal = AppSpacing.lg,
                vertical = AppSpacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item(key = "list-shortcut") {
                ShoppingListShortcutCard(listItemCount, onOpenList)
            }
            item(key = "spending") {
                MonthSpendingCard(receiptState.byMonth, receiptState.selectedMonth)
            }
            item(key = "weekly-chart") {
                WeeklyBarChart(weekBars(receiptState.selectedMonth, receiptState.byWeek))
            }
            item(key = "tabs") {
                DashboardTabRow(selected = tab, onSelect = { tab = it })
            }
            val (rows, emptyText) = when (tab) {
                DashboardTab.RECEIPTS -> monthReceipts.map {
                    DashboardRow(it.shopName, receiptTimestamp(it.issuedAt), money(it.totalAmount))
                } to "Nema računa za ovaj mesec."

                DashboardTab.SHOPS -> receiptState.byShop.map {
                    DashboardRow(
                        it.shopName,
                        counted(it.receipts, "račun", "računa", "računa"),
                        money(it.spent)
                    )
                } to "Još nema podataka o prodavnicama."

                DashboardTab.CATEGORIES -> receiptState.byCategory.map {
                    DashboardRow(it.category, trailing = money(it.spent))
                } to "Nema računa za ovaj mesec."

                // Navike su za sve vreme, ne za izabrani mesec: to je ono što
                // kupac obično kupuje, a ne šta je kupio u junu.
                DashboardTab.HABITS -> receiptState.habits.map { habit ->
                    DashboardRow(
                        habit.name,
                        listOfNotNull(
                            counted(habit.times, "put", "puta", "puta"),
                            belgradeDay(habit.lastBought)?.let { "poslednji put ${date(it.toString())}" }
                        ).joinToString(" • ")
                    )
                } to "Navike se vide kad skeniraš račune sa stavkama."
            }
            item(key = tab.name) {
                if (rows.isEmpty()) EmptyTabMessage(emptyText) else DashboardRows(rows)
            }
        }
    }
}

@Composable
private fun ShoppingListShortcutCard(itemCount: Int, onOpenList: () -> Unit) {
    Surface(
        onClick = onOpenList,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("open-list")
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                R.drawable.ic_content_paste,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.width(AppSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Moj spisak",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    if (itemCount == 0) "Prazan" else items(itemCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            AppIcon(
                R.drawable.ic_chevron_right,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun MonthSpendingCard(
    byMonth: List<MonthlySpendingDto>,
    selectedMonth: LocalDate
) {
    val month = byMonth.find { it.month == selectedMonth.toString() }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
        ) {
            Text(
                money(month?.spent ?: 0.0),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                "Ukupni troškovi • ${counted(month?.receipts ?: 0, "račun", "računa", "računa")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            label = "$startDay-$endDay",
            spent = byBucket[bucket]?.spent ?: 0.0
        )
    }
}

/**
 * Bez chart biblioteke: visina stuba je udeo fiksne visine kolone
 * (Spacer + stub, oba sa weight-om, uvek zbir 1), kao na mani.rs.
 */
@Composable
private fun WeeklyBarChart(weeks: List<WeekBar>, modifier: Modifier = Modifier) {
    val maxSpent = (weeks.maxOfOrNull { it.spent } ?: 0.0).coerceAtLeast(1.0)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                weeks.forEach { week ->
                    val fraction = (week.spent / maxSpent).toFloat().coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (fraction < 1f) {
                            Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.01f)))
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.6f)
                                .weight(fraction.coerceAtLeast(0.02f))
                                .background(
                                    color = if (week.spent > 0) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    shape = RoundedCornerShape(
                                        topStart = AppSpacing.xs,
                                        topEnd = AppSpacing.xs
                                    )
                                )
                        )
                    }
                }
            }
            Spacer(Modifier.height(AppSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                weeks.forEach { week ->
                    Text(
                        week.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardTabRow(selected: DashboardTab, onSelect: (DashboardTab) -> Unit) {
    TabRow(selectedTabIndex = selected.ordinal) {
        DashboardTab.entries.forEach { tab ->
            // Bez podrazumevanih 16 dp sa strane teksta: četiri naziva staju
            // i na uzak telefon, umesto „Prodavnic".
            Tab(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(tab.tag)
            ) {
                Text(tab.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

private data class DashboardRow(
    val title: String,
    val subtitle: String? = null,
    val trailing: String? = null
)

@Composable
private fun DashboardRows(rows: List<DashboardRow>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = AppSpacing.lg))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.lg)
                ) {
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
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTabMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = AppSpacing.lg)
    )
}

private fun monthTitle(month: LocalDate): String =
    monthName(month).replaceFirstChar(Char::uppercase)

private fun belgradeDay(instantIso: String): LocalDate? = try {
    Instant.parse(instantIso).atZone(BELGRADE).toLocalDate()
} catch (_: Exception) {
    null
}

private fun isInMonth(issuedAtIso: String, month: LocalDate): Boolean =
    belgradeDay(issuedAtIso)?.let { it.year == month.year && it.month == month.month } ?: false

private fun receiptTimestamp(issuedAtIso: String): String = try {
    dateTime(Instant.parse(issuedAtIso).toEpochMilli())
} catch (_: Exception) {
    issuedAtIso
}
