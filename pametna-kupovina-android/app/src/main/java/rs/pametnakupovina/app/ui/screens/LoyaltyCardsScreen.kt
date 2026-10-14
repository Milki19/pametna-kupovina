package rs.pametnakupovina.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.em
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.enter
import rs.pametnakupovina.app.ui.components.rememberEntrance
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.graphics.Color
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.network.LoyaltyCardDto
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.ui.components.TitleAction
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
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.listBottomPadding
import rs.pametnakupovina.app.ui.components.AppTopBar
import rs.pametnakupovina.app.ui.components.Barcode
import rs.pametnakupovina.app.ui.components.isValidEan13
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
    val entrance = rememberEntrance()

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
                        onValueChange = {
                            number = it
                            // Skeniran EAN-13 koji je posle ručno izmenjen više nije EAN-13:
                            // oblik se vraća na crtični kod koji prima bilo koji broj.
                            if (format == "EAN_13" && !isValidEan13(it.trim())) format = "CODE_128"
                        },
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
        containerColor = Color.Transparent,
        topBar = {
            AppTopBar(
                title = stringResource(R.string.loyalty_title),
                subtitle = stringResource(R.string.loyalty_subtitle),
                onBack = onBack,
                actions = {
                    TitleAction(
                        text = stringResource(R.string.loyalty_add),
                        icon = R.drawable.ic_add,
                        onClick = { adding = true },
                        modifier = Modifier.testTag("add-card")
                    )
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .testTag("loyalty-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = listBottomPadding()
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

            if (state.cards.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.loyalty_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            itemsIndexed(state.cards, key = { _, card -> card.id }) { index, card ->
                LoyaltyCard(
                    card = card,
                    tone = CardTones[index % CardTones.size],
                    open = state.shown?.id == card.id,
                    onToggle = { viewModel.show(card.takeUnless { state.shown?.id == card.id }) },
                    onDelete = { viewModel.remove(card.id) },
                    modifier = Modifier.enter(entrance, index + 1)
                )
            }
        }
    }
}

/** Boje kartica; iste su u svim temama, sa belim slovima. */
private val CardTones = listOf(Color(0xFF2F4BD8), Color(0xFFB23A0A), Color(0xFF33415C))

/**
 * Kartica u boji sa odsjajem. Dodir otvara barkod ispod nje, na belom, jer
 * čitač na kasi drugo ne čita; tu su i broj za kucanje i brisanje.
 */
@Composable
private fun LoyaltyCard(
    card: LoyaltyCardDto,
    tone: Color,
    open: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val expandDescription = stringResource(R.string.loyalty_tap_to_enlarge)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard(
            onClick = onToggle,
            color = tone,
            contentColor = Color.White,
            border = null,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { stateDescription = if (open) "" else expandDescription }
                .testTag("card-${card.id}")
        ) {
            Box {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Brush.linearGradient(0f to Color.White.copy(alpha = 0.28f), 0.42f to Color.Transparent))
                )
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.name,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f)
                        )
                        AppIcon(R.drawable.ic_card, contentDescription = null)
                    }
                    Text(
                        "•••• " + card.cardNumber.takeLast(4),
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.12.em),
                        modifier = Modifier.alpha(0.9f)
                    )
                }
            }
        }
        // Barkod se otvara animacijom visine.
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(tween(450, easing = EaseOutCubic)) + fadeIn(),
            exit = shrinkVertically(tween(300)) + fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                contentColor = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Barcode(
                        value = card.cardNumber,
                        format = card.barcodeFormat,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .testTag("card-barcode")
                    )
                    Text(
                        card.cardNumber,
                        style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.12.em),
                        textAlign = TextAlign.Center
                    )
                    Text(
                        stringResource(R.string.loyalty_cashier_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF545C70),
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB3261E))
                    ) {
                        Text(stringResource(R.string.loyalty_delete))
                    }
                }
            }
        }
    }
}
