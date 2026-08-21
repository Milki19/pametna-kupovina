package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.data.network.ProductCandidateDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchResultDto
import rs.pametnakupovina.app.data.network.ShoppingItemMatchingStatusDto
import rs.pametnakupovina.app.ui.MatchingViewModel
import rs.pametnakupovina.app.ui.components.ErrorState
import rs.pametnakupovina.app.ui.components.LoadingState
import rs.pametnakupovina.app.ui.components.MetricRow
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.StatusTone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchingScreen(
    onBack: () -> Unit,
    onContinue: (Long) -> Unit,
    viewModel: MatchingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Provera proizvoda") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Nazad") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> LoadingState("Tražim najbolje kandidate…")
                state.errorMessage != null && state.result == null -> {
                    ErrorState(
                        title = "Provera nije uspela",
                        message = requireNotNull(state.errorMessage),
                        onRetry = viewModel::load
                    )
                }
                state.result != null -> {
                    val result = requireNotNull(state.result)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "Sažetak",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    MetricRow(
                                        connectedItems(result).toString() to
                                            "povezano",
                                        result.itemsNeedingConfirmation.toString() to
                                            "za potvrdu",
                                        result.unmatchedItems.toString() to
                                            "neupareno"
                                    )
                                }
                            }
                        }

                        state.errorMessage?.let { message ->
                            item {
                                Text(
                                    message,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        items(result.items, key = { it.itemId }) { item ->
                            MatchingItemCard(
                                item = item,
                                enabled = !state.isResolving,
                                onChoose = { candidate ->
                                    viewModel.confirm(item, candidate)
                                }
                            )
                        }

                        item {
                            Button(
                                enabled = result.readyForOptimization &&
                                    !state.isResolving,
                                onClick = { onContinue(result.listId) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (result.readyForOptimization) {
                                        "Nastavi na lokaciju"
                                    } else {
                                        "Potvrdi označene stavke"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchingItemCard(
    item: ShoppingItemMatchResultDto,
    enabled: Boolean,
    onChoose: (ProductCandidateDto?) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(item.requestedName, style = MaterialTheme.typography.titleMedium)
            StatusPill(
                text = statusText(item.matchingStatus),
                tone = statusTone(item.matchingStatus)
            )
            Text(item.explanation, style = MaterialTheme.typography.bodySmall)

            if (item.matchingStatus == ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION) {
                item.candidates.take(5).forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        enabled = enabled,
                        onChoose = { onChoose(candidate) }
                    )
                }
                TextButton(
                    enabled = enabled,
                    onClick = { onChoose(null) }
                ) {
                    Text("Nijedan — ostavi neupareno")
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.name, style = MaterialTheme.typography.titleSmall)
                val details = listOfNotNull(
                    candidate.brand,
                    candidate.quantityValue?.let { quantity ->
                        "$quantity ${candidate.baseUnit.orEmpty()}".trim()
                    }
                ).joinToString(" • ")
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Pouzdanost: ${(candidate.score.totalScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Button(enabled = enabled, onClick = onChoose) {
                Text("Izaberi")
            }
        }
    }
}

private fun statusText(status: ShoppingItemMatchingStatusDto): String = when (status) {
    ShoppingItemMatchingStatusDto.PENDING -> "Čeka proveru"
    ShoppingItemMatchingStatusDto.AUTO_MATCHED -> "Automatski povezano"
    ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION -> "Potrebna je tvoja potvrda"
    ShoppingItemMatchingStatusDto.CONFIRMED -> "Potvrđeno"
    ShoppingItemMatchingStatusDto.UNMATCHED -> "Neupareno"
}

private fun statusTone(status: ShoppingItemMatchingStatusDto): StatusTone =
    when (status) {
        ShoppingItemMatchingStatusDto.AUTO_MATCHED,
        ShoppingItemMatchingStatusDto.CONFIRMED -> StatusTone.POSITIVE
        ShoppingItemMatchingStatusDto.PENDING,
        ShoppingItemMatchingStatusDto.NEEDS_CONFIRMATION -> StatusTone.WARNING
        ShoppingItemMatchingStatusDto.UNMATCHED -> StatusTone.ERROR
    }

internal fun connectedItems(result: rs.pametnakupovina.app.data.network.ShoppingListMatchingDto): Int =
    result.automaticallyMatchedItems + result.confirmedItems
