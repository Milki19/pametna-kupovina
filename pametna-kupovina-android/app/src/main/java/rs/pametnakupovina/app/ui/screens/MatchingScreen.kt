package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.ProductCandidateDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto
import rs.pametnakupovina.app.ui.MatchingViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.cardBorder
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.SectionHeader
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StepCard
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.counted
import rs.pametnakupovina.app.ui.plural

@Composable
fun MatchingScreen(
    onBack: () -> Unit,
    onContinue: (Long) -> Unit,
    viewModel: MatchingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val result = state.result

    Scaffold(
        topBar = { AppTopBar(title = "Provera proizvoda", onBack = onBack) },
        bottomBar = {
            if (result != null) {
                val pending = pendingDecisions(result)
                BottomActionBar {
                    PrimaryActionButton(
                        text = when {
                            state.isResolving -> "Čuvam odluku…"
                            result.readyForOptimization -> "Nastavi na lokaciju"
                            pending > 0 -> "Odluči još za " +
                                counted(pending, "stavku", "stavke", "stavki")
                            else -> "Potvrdi označene stavke"
                        },
                        enabled = result.readyForOptimization && !state.isResolving,
                        onClick = { onContinue(result.listId) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> LoadingState("Tražim odgovarajuće proizvode…")
                state.errorMessage != null && result == null -> ErrorState(
                    title = "Provera nije uspela",
                    message = requireNotNull(state.errorMessage),
                    onRetry = viewModel::load
                )
                result != null -> MatchingContent(
                    result = result,
                    errorMessage = state.errorMessage,
                    enabled = !state.isResolving,
                    onChoose = viewModel::confirm,
                    onUseAsFlexible = viewModel::useAsFlexible
                )
            }
        }
    }
}

/**
 * What needs a decision comes first and open; what is already settled is a
 * short checklist underneath. Before, five identical "recognised" cards stood
 * between the reader and the button.
 */
@Composable
private fun MatchingContent(
    result: ShoppingListMatchingDto,
    errorMessage: String?,
    enabled: Boolean,
    onChoose: (ShoppingItemMatchResultDto, ProductCandidateDto?) -> Unit,
    onUseAsFlexible: (ShoppingItemMatchResultDto) -> Unit
) {
    val (needsDecision, settled) = result.items.partition(::needsDecision)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        item(key = "summary") {
            MatchingSummary(result, pending = needsDecision.size)
        }

        errorMessage?.let { message ->
            item(key = "error") {
                NoticeBanner(text = message, tone = StatusTone.ERROR)
            }
        }

        if (needsDecision.isNotEmpty()) {
            item(key = "decide-header") {
                SectionHeader("Treba tvoja odluka", trailing = needsDecision.size.toString())
            }
            itemsIndexed(needsDecision, key = { _, item -> item.itemId }) { index, item ->
                DecisionCard(
                    item = item,
                    // Isti razlog na deset kartica je deset puta isti pasus.
                    // Piše se samo kad se promeni u odnosu na karticu iznad.
                    showExplanation = index == 0 ||
                        needsDecision[index - 1].explanation != item.explanation,
                    enabled = enabled,
                    onChoose = { candidate -> onChoose(item, candidate) },
                    onUseAsFlexible = { onUseAsFlexible(item) }
                )
            }
        }

        if (settled.isNotEmpty()) {
            item(key = "settled-header") {
                SectionHeader("Prepoznato", trailing = settled.size.toString())
            }
            item(key = "settled") {
                SettledList(settled)
            }
        }
    }
}

/** Korak u toku izračunavanja i koliko je stavki već prepoznato. */
@Composable
private fun MatchingSummary(result: ShoppingListMatchingDto, pending: Int) {
    val connected = connectedItems(result)
    StepCard(
        step = "KORAK 1 OD 3",
        title = if (pending == 0) {
            "Sve stavke su prepoznate"
        } else {
            "$pending ${plural(pending, "stavka traži", "stavke traže", "stavki traži")} tvoju odluku"
        },
        text = "Potvrdi nejasne stavke da bismo našli najpovoljnije cene u blizini."
    ) {
        Row {
            Text(
                "Prepoznato $connected od ${result.totalItems}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (pending > 0) {
                Text(
                    "još $pending",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        LinearProgressIndicator(
            progress = { if (result.totalItems > 0) connected.toFloat() / result.totalItems else 0f },
            drawStopIndicator = {},
            gapSize = 0.dp,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
    }
}

@Composable
private fun DecisionCard(
    item: ShoppingItemMatchResultDto,
    showExplanation: Boolean,
    enabled: Boolean,
    onChoose: (ProductCandidateDto?) -> Unit,
    onUseAsFlexible: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Sa spiska",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("„${item.requestedName}“", style = MaterialTheme.typography.titleLarge)
                }
                StatusPill(statusText(item.matchingStatus), statusTone(item.matchingStatus))
            }
            if (showExplanation) {
                Text(
                    item.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION) {
                item.candidates.take(5).forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        enabled = enabled,
                        onChoose = { onChoose(candidate) }
                    )
                }
            }

            if (canUseAsFlexible(item)) {
                Surface(
                    onClick = onUseAsFlexible,
                    enabled = enabled,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "Neka aplikacija izabere najpovoljnije",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Kad ti nije važan brend ni pakovanje.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION) {
                TextButton(
                    enabled = enabled,
                    onClick = { onChoose(null) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Nijedan, ostavi neupareno", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Ceo red se bira dodirom; „Izaberi" kaže šta dodir radi. */
@Composable
private fun CandidateRow(
    candidate: ProductCandidateDto,
    enabled: Boolean,
    onChoose: () -> Unit
) {
    Surface(
        onClick = onChoose,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    StatusPill("${(candidate.score.totalScore * 100).toInt()}%", StatusTone.POSITIVE)
                    Text(
                        listOfNotNull(
                            candidate.brand,
                            candidate.quantityValue?.let { quantity ->
                                candidate.baseUnit?.let { amountLabel(quantity, it, candidate.packageCount) }
                            }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Izaberi",
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun SettledList(items: List<ShoppingItemMatchResultDto>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    AppIcon(
                        R.drawable.ic_check_circle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.requestedName, style = MaterialTheme.typography.titleMedium)
                        // A confirmed item needs no story; an automatic match
                        // says what was picked, which is worth a glance.
                        if (item.matchingStatus == ShoppingItemMatchingStatusDto.AUTO_MATCHED) {
                            Text(
                                item.explanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 54.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

private fun needsDecision(item: ShoppingItemMatchResultDto): Boolean =
    item.blocksOptimization ||
        item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION ||
        item.matchingStatus == ShoppingItemMatchingStatusDto.UNMATCHED

private fun pendingDecisions(result: ShoppingListMatchingDto): Int =
    result.blockingItemIds.size.takeIf { it > 0 }
        ?: (result.itemsNeedingConfirmation + result.unmatchedItems)

private fun statusText(status: ShoppingItemMatchingStatusDto): String = when (status) {
    ShoppingItemMatchingStatusDto.PENDING -> "Čeka proveru"
    ShoppingItemMatchingStatusDto.AUTO_MATCHED -> "Automatski"
    ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION -> "Treba potvrda"
    ShoppingItemMatchingStatusDto.CONFIRMED -> "Potvrđeno"
    ShoppingItemMatchingStatusDto.UNMATCHED -> "Nije pronađeno"
}

private fun statusTone(status: ShoppingItemMatchingStatusDto): StatusTone =
    when (status) {
        ShoppingItemMatchingStatusDto.AUTO_MATCHED,
        ShoppingItemMatchingStatusDto.CONFIRMED -> StatusTone.POSITIVE
        ShoppingItemMatchingStatusDto.PENDING,
        ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION -> StatusTone.WARNING
        ShoppingItemMatchingStatusDto.UNMATCHED -> StatusTone.ERROR
    }

internal fun connectedItems(result: ShoppingListMatchingDto): Int =
    result.automaticallyMatchedItems + result.confirmedItems

internal fun canUseAsFlexible(item: ShoppingItemMatchResultDto): Boolean =
    item.matchingRule == ShoppingItemRuleDto.EXACT_PRODUCT &&
        item.matchingStatus == ShoppingItemMatchingStatusDto.UNMATCHED
