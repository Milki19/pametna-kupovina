package rs.pametnakupovina.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.LoyaltyCardDto
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.ui.components.LetterTile
import rs.pametnakupovina.app.ui.components.StatusPill
import rs.pametnakupovina.app.ui.components.TonalActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.ui.LoyaltyViewModel
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.components.cardBorder
import rs.pametnakupovina.app.ui.components.heroColors
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.Barcode
import rs.pametnakupovina.app.ui.components.NoticeBanner
import rs.pametnakupovina.app.ui.components.StatusTone

/**
 * Novčanik pun plastike, a kasirki treba samo broj. Kartica se otvara na ceo
 * ekran, jer se tu i koristi — u redu, na kasi, iz prve.
 */
@Composable
fun LoyaltyCardsScreen(
    onBack: (() -> Unit)?,
    viewModel: LoyaltyViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var number by rememberSaveable { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("CODE_128") }

    state.shown?.let { card ->
        AlertDialog(
            onDismissRequest = { viewModel.show(null) },
            title = { Text(card.name) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Barcode(
                        value = card.cardNumber,
                        format = card.barcodeFormat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .testTag("card-barcode")
                    )
                    Text(
                        card.cardNumber,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        stringResource(R.string.loyalty_cashier_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.show(null) }) { Text(stringResource(R.string.common_close)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.remove(card.id) }) {
                    Text(stringResource(R.string.loyalty_delete))
                }
            }
        )
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.loyalty_new_card)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.loyalty_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("card-name")
                    )
                    OutlinedTextField(
                        value = number,
                        onValueChange = { number = it },
                        label = { Text(stringResource(R.string.loyalty_number)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("card-number")
                    )
                    TonalActionButton(
                        text = stringResource(if (state.busy) R.string.loyalty_scanning else R.string.loyalty_scan),
                        icon = R.drawable.ic_camera,
                        enabled = !state.busy,
                        onClick = {
                            viewModel.scanCard(context) { scanned, scannedFormat ->
                                number = scanned
                                format = scannedFormat
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (number.isNotBlank()) {
                        Text(
                            stringResource(R.string.loyalty_code_format, format),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && number.isNotBlank(),
                    onClick = {
                        viewModel.add(name.trim(), number.trim(), format)
                        name = ""
                        number = ""
                        format = "CODE_128"
                        adding = false
                    }
                ) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) { Text(stringResource(R.string.loyalty_cancel)) }
            }
        )
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.loyalty_title), onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("loyalty-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = AppSpacing.xl
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            state.message?.let {
                item(key = "message") {
                    NoticeBanner(
                        text = it.asString(),
                        tone = StatusTone.ERROR,
                        actionLabel = stringResource(R.string.dashboard_ok),
                        onAction = viewModel::dismissMessage
                    )
                }
            }

            item(key = "add") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Text(
                        stringResource(R.string.loyalty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TonalActionButton(
                        text = stringResource(R.string.loyalty_add),
                        icon = R.drawable.ic_add,
                        primary = true,
                        onClick = { adding = true },
                        modifier = Modifier.testTag("add-card")
                    )
                }
            }

            if (state.cards.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.loyalty_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Prva kartica je odmah spremna za čitač, kao na nacrtu; ostale
                // se otvaraju dodirom.
                val first = state.cards.first()
                item(key = "featured") {
                    FeaturedCard(first, onOpen = { viewModel.show(first) })
                }
                if (state.cards.size > 1) {
                    item(key = "wallet-header") {
                        Text(
                            stringResource(R.string.loyalty_wallet),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = AppSpacing.sm)
                        )
                    }
                }
                items(state.cards.drop(1), key = { it.id }) { card ->
                    Surface(
                        onClick = { viewModel.show(card) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = cardBorder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                            modifier = Modifier.padding(AppSpacing.lg)
                        ) {
                            LetterTile(card.name)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(card.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    card.cardNumber,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusPill(stringResource(R.string.loyalty_barcode), StatusTone.POSITIVE)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedCard(card: LoyaltyCardDto, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = heroColors().container,
        contentColor = heroColors().content,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Text(card.name, style = MaterialTheme.typography.titleLarge)
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = Color.White,
                contentColor = Color.Black,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.md),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    // Crno na belom i u tamnoj temi: čitač na kasi drugo ne čita.
                    Barcode(
                        value = card.cardNumber,
                        format = card.barcodeFormat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                    )
                    Text(card.cardNumber, style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                stringResource(R.string.loyalty_tap_to_enlarge),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
