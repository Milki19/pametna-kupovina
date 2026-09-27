package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.CanonicalProductSearchItemDto
import rs.pametnakupovina.app.ui.ProductSearchUiState
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.FullScreenDialog
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.StatusTone
import rs.pametnakupovina.app.ui.components.canonicalProductPicker

@Composable
internal fun AlternativePickerDialog(
    name: String,
    state: ProductSearchUiState,
    saving: Boolean,
    error: String?,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (CanonicalProductSearchItemDto, Double) -> Unit
) {
    var selected by remember(name) { mutableStateOf<CanonicalProductSearchItemDto?>(null) }
    var packages by remember(name) { mutableStateOf("1") }
    val amount = packages.replace(',', '.').toDoubleOrNull()
    val canSave = !saving &&
        selected?.hasUsablePrice == true &&
        amount != null && amount.isFinite() && amount > 0

    FullScreenDialog(
        title = stringResource(R.string.rec_alternative_title, name),
        confirmLabel = if (saving) {
            stringResource(R.string.rec_alternative_calculating)
        } else {
            stringResource(R.string.rec_replace)
        },
        confirmEnabled = canSave,
        confirmModifier = Modifier.testTag("confirm-alternative"),
        onConfirm = { onSave(requireNotNull(selected), requireNotNull(amount)) },
        onDismiss = { if (!saving) onDismiss() }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .testTag("alternatives-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.sm,
                bottom = AppSpacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            item(key = "alternative-help") {
                Text(
                    stringResource(R.string.rec_alternative_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            canonicalProductPicker(
                query = state.query,
                selectedProduct = selected,
                searchState = state,
                onQueryChange = {
                    selected = null
                    onQuery(it)
                },
                onSelectProduct = { selected = it },
                onClearSelection = { selected = null },
                onRetry = onRetry,
                onLoadMore = onMore,
                showWithoutPriceFilter = false
            )
            item(key = "alternative-packages") {
                OutlinedTextField(
                    value = packages,
                    onValueChange = { packages = it },
                    enabled = !saving,
                    label = { Text(stringResource(R.string.rec_alternative_packages)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            error?.let { message ->
                item(key = "alternative-error") {
                    NoticeBanner(text = message, tone = StatusTone.ERROR)
                }
            }
        }
    }
}
