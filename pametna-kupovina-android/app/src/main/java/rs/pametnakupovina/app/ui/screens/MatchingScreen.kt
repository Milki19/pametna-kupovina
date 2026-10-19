package rs.pametnakupovina.app.ui.screens

import androidx.annotation.StringRes
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.RequestedPackages
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.ProductCandidateDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.ShoppingListMatchingDto
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.ui.MatchingViewModel
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.GlassCard
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

@Composable
fun MatchingScreen(
    onBack: () -> Unit,
    onContinue: (Long) -> Unit,
    viewModel: MatchingViewModel = hiltViewModel(),
    productSearchViewModel: ProductSearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val result = state.result
    val errorMessage = state.errorMessage?.asString()
    val searchState by productSearchViewModel.uiState.collectAsStateWithLifecycle()
    // „Izaberi iz pretrage": isti izbor proizvoda kao zamena u planu.
    var pickFor by remember { mutableStateOf<ShoppingItemMatchResultDto?>(null) }
    var pickError by remember { mutableStateOf<UiText?>(null) }
    var pickRequested by remember { mutableStateOf<RequestedPackages?>(null) }
    LaunchedEffect(pickFor?.itemId) {
        pickRequested = null
        pickFor?.let { item ->
            productSearchViewModel.clear()
            productSearchViewModel.updateQuery(item.requestedName)
            pickRequested = runCatching { viewModel.requestedPackages(item.itemId) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
        }
    }
    pickFor?.let { item ->
        AlternativePickerDialog(
            name = item.requestedName,
            state = searchState,
            saving = state.isResolving,
            error = pickError?.asString(),
            onQuery = productSearchViewModel::updateQuery,
            onRetry = productSearchViewModel::retry,
            onMore = productSearchViewModel::loadNextPage,
            onDismiss = {
                if (!state.isResolving) {
                    pickFor = null
                    productSearchViewModel.clear()
                }
            },
            requested = pickRequested,
            onSave = { product, packages ->
                pickError = null
                viewModel.pickFromSearch(item, product, packages) { error ->
                    pickError = error
                    if (error == null) {
                        pickFor = null
                        productSearchViewModel.clear()
                    }
                }
            }
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { AppTopBar(title = stringResource(R.string.match_title), onBack = onBack) },
        bottomBar = {
            if (result != null) {
                val pending = result.items.count(::needsDecision)
                BottomActionBar {
                    PrimaryActionButton(
                        text = when {
                            state.isResolving -> stringResource(R.string.match_saving)
                            result.readyForOptimization -> stringResource(R.string.match_continue)
                            pending > 0 -> pluralStringResource(R.plurals.match_decide_more, pending, pending)
                            else -> stringResource(R.string.match_confirm_marked)
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
                state.isLoading -> LoadingState(stringResource(R.string.match_loading))
                errorMessage != null && result == null -> ErrorState(
                    title = stringResource(R.string.match_error_title),
                    message = errorMessage,
                    onRetry = viewModel::load
                )
                result != null -> MatchingContent(
                    result = result,
                    errorMessage = errorMessage,
                    enabled = !state.isResolving,
                    onChoose = viewModel::confirm,
                    onUseAsFlexible = viewModel::useAsFlexible,
                    onPick = {
                        pickError = null
                        pickFor = it
                    }
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
    onUseAsFlexible: (ShoppingItemMatchResultDto) -> Unit,
    onPick: (ShoppingItemMatchResultDto) -> Unit
) {
    // Jedno stanje kao na spisku i u planu: treba odluka, nije pronađeno, pronađeno.
    val decide = result.items.filter { needsDecision(it) }
    val missing = result.items.filter { notFound(it) }
    val settled = result.items.filter { isFound(it) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        item(key = "summary") {
            MatchingSummary(result, pending = decide.size, missing = missing.size)
        }

        errorMessage?.let { message ->
            item(key = "error") {
                NoticeBanner(text = message, tone = StatusTone.ERROR)
            }
        }

        if (decide.isNotEmpty()) {
            item(key = "decide-header") {
                SectionHeader(stringResource(R.string.match_section_decide), trailing = decide.size.toString())
            }
            itemsIndexed(decide, key = { _, item -> item.itemId }) { index, item ->
                DecisionCard(
                    item = item,
                    // Isti razlog na deset kartica je deset puta isti pasus.
                    // Piše se samo kad se promeni u odnosu na karticu iznad.
                    showExplanation = index == 0 ||
                        decide[index - 1].explanation != item.explanation,
                    enabled = enabled,
                    onChoose = { candidate -> onChoose(item, candidate) },
                    onUseAsFlexible = { onUseAsFlexible(item) },
                    onPick = { onPick(item) }
                )
            }
        }

        if (missing.isNotEmpty()) {
            item(key = "not-found-header") {
                SectionHeader(stringResource(R.string.match_section_not_found), trailing = missing.size.toString())
            }
            itemsIndexed(missing, key = { _, item -> "nf-${item.itemId}" }) { index, item ->
                DecisionCard(
                    item = item,
                    showExplanation = index == 0 ||
                        missing[index - 1].explanation != item.explanation,
                    enabled = enabled,
                    onChoose = { candidate -> onChoose(item, candidate) },
                    onUseAsFlexible = { onUseAsFlexible(item) },
                    onPick = { onPick(item) }
                )
            }
        }

        if (settled.isNotEmpty()) {
            item(key = "settled-header") {
                SectionHeader(stringResource(R.string.match_section_settled), trailing = settled.size.toString())
            }
            item(key = "settled") {
                SettledList(settled, enabled = enabled, onPick = onPick)
            }
        }
    }
}

/** Korak u toku izračunavanja i koliko je stavki već prepoznato. */
@Composable
private fun MatchingSummary(result: ShoppingListMatchingDto, pending: Int, missing: Int) {
    val connected = connectedItems(result)
    StepCard(
        step = stringResource(R.string.match_step),
        title = when {
            pending > 0 -> pluralStringResource(R.plurals.match_pending_title, pending, pending)
            missing > 0 -> pluralStringResource(R.plurals.match_not_found_title, missing, missing)
            else -> stringResource(R.string.match_all_recognized)
        },
        text = stringResource(
            if (pending == 0 && missing > 0) R.string.match_not_found_text else R.string.match_summary_text
        )
    ) {
        Row {
            Text(
                stringResource(R.string.match_recognized_of, connected, result.totalItems),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (pending > 0) {
                Text(
                    stringResource(R.string.match_remaining, pending),
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
    onUseAsFlexible: () -> Unit,
    onPick: () -> Unit
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.match_from_list),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(stringResource(R.string.match_requested_name, item.requestedName), style = MaterialTheme.typography.titleLarge)
                }
                StatusPill(stringResource(statusText(item.matchingStatus)), statusTone(item.matchingStatus))
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
                            stringResource(R.string.match_flexible_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            stringResource(R.string.match_flexible_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            FilledTonalButton(
                onClick = onPick,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("pick-from-search-${item.itemId}")
            ) {
                Text(stringResource(R.string.match_pick_from_search))
            }

            if (item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION) {
                TextButton(
                    enabled = enabled,
                    onClick = { onChoose(null) },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text(stringResource(R.string.match_none_leave_unmatched), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                stringResource(R.string.match_choose),
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
private fun SettledList(
    items: List<ShoppingItemMatchResultDto>,
    enabled: Boolean,
    onPick: (ShoppingItemMatchResultDto) -> Unit
) {
    GlassCard(
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
                    TextButton(enabled = enabled, onClick = { onPick(item) }) {
                        Text(stringResource(R.string.match_change))
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

internal fun isFound(item: ShoppingItemMatchResultDto): Boolean =
    item.matchingStatus == ShoppingItemMatchingStatusDto.AUTO_MATCHED ||
        item.matchingStatus == ShoppingItemMatchingStatusDto.CONFIRMED

/** Samo ovo zadržava korak: nepronađena stavka ne ulazi u račun, ali ne blokira. */
internal fun needsDecision(item: ShoppingItemMatchResultDto): Boolean =
    item.blocksOptimization ||
        item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION

internal fun notFound(item: ShoppingItemMatchResultDto): Boolean =
    !needsDecision(item) && !isFound(item)

@StringRes
private fun statusText(status: ShoppingItemMatchingStatusDto): Int = when (status) {
    ShoppingItemMatchingStatusDto.PENDING -> R.string.match_status_pending
    ShoppingItemMatchingStatusDto.AUTO_MATCHED -> R.string.match_status_auto
    ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION -> R.string.match_status_needs_confirmation
    ShoppingItemMatchingStatusDto.CONFIRMED -> R.string.match_status_confirmed
    ShoppingItemMatchingStatusDto.UNMATCHED -> R.string.match_status_unmatched
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
