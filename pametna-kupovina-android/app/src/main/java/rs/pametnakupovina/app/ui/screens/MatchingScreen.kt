package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
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
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.BottomActionBar
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.PrimaryActionButton
import rs.pametnakupovina.app.ui.components.SectionHeader
import rs.pametnakupovina.app.ui.components.StatusPill
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
            items(needsDecision, key = { it.itemId }) { item ->
                DecisionCard(
                    item = item,
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

@Composable
private fun MatchingSummary(result: ShoppingListMatchingDto, pending: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        modifier = Modifier.padding(vertical = AppSpacing.sm)
    ) {
        AppIcon(
            if (pending == 0) R.drawable.ic_check_circle else R.drawable.ic_warning,
            contentDescription = null,
            tint = if (pending == 0) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.tertiary
            },
            modifier = Modifier.size(36.dp)
        )
        Column {
            Text(
                if (pending == 0) {
                    "Sve stavke su prepoznate"
                } else {
                    "$pending ${plural(pending, "stavka traži", "stavke traže", "stavki traži")} tvoju odluku"
                },
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Prepoznato ${connectedItems(result)} od ${result.totalItems}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DecisionCard(
    item: ShoppingItemMatchResultDto,
    enabled: Boolean,
    onChoose: (ProductCandidateDto?) -> Unit,
    onUseAsFlexible: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.requestedName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(statusText(item.matchingStatus), statusTone(item.matchingStatus))
            }
            Text(
                item.explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION) {
                item.candidates.take(5).forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        enabled = enabled,
                        onChoose = { onChoose(candidate) }
                    )
                }
                TextButton(enabled = enabled, onClick = { onChoose(null) }) {
                    Text("Nijedan, ostavi neupareno")
                }
            }

            if (canUseAsFlexible(item)) {
                Text(
                    "Ako ti nije važan brend ni pakovanje, aplikacija može sama da izabere najpovoljniju ponudu.",
                    style = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(
                    enabled = enabled,
                    onClick = onUseAsFlexible,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Neka aplikacija izabere")
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: ProductCandidateDto,
    enabled: Boolean,
    onChoose: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(
                start = AppSpacing.md,
                end = AppSpacing.sm,
                top = AppSpacing.sm,
                bottom = AppSpacing.sm
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        candidate.brand,
                        candidate.quantityValue?.let { quantity ->
                            candidate.baseUnit?.let { amountLabel(quantity, it, candidate.packageCount) }
                        },
                        "poklapanje ${(candidate.score.totalScore * 100).toInt()}%"
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(enabled = enabled, onClick = onChoose) {
                Text("Izaberi")
            }
        }
    }
}

@Composable
private fun SettledList(items: List<ShoppingItemMatchResultDto>) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
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
