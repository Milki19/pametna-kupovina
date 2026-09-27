package rs.pametnakupovina.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import kotlin.math.abs
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.data.amountLabel
import rs.pametnakupovina.app.data.network.OptimizationScenarioDto
import rs.pametnakupovina.app.data.network.PurchaseQuantityDto
import rs.pametnakupovina.app.data.network.RecommendationItemDto
import rs.pametnakupovina.app.data.network.ShoppingItemRuleDto
import rs.pametnakupovina.app.data.network.RecommendationItemStatusDto
import rs.pametnakupovina.app.data.network.RecommendationScenarioTypeDto
import rs.pametnakupovina.app.data.network.RecommendationStoreDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.UnlocatedPriceOptionDto
import rs.pametnakupovina.app.data.purchase.PurchaseSession
import rs.pametnakupovina.app.location.Coordinates
import rs.pametnakupovina.app.text.UiText
import rs.pametnakupovina.app.text.asString
import rs.pametnakupovina.app.text.uiPlural
import rs.pametnakupovina.app.text.uiText
import rs.pametnakupovina.app.navigation.googleMapsDirectionsUrl
import rs.pametnakupovina.app.navigation.launchGoogleMapsDirections
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.PurchaseViewModel
import rs.pametnakupovina.app.ui.RecommendationViewModel
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.TonalActionButton
import rs.pametnakupovina.app.ui.components.cardBorder
import androidx.annotation.StringRes
import androidx.compose.ui.text.style.TextAlign
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
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.decimal
import rs.pametnakupovina.app.ui.distance
import rs.pametnakupovina.app.ui.duration
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.wholeDinars
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import rs.pametnakupovina.app.data.similarKind
import androidx.compose.material3.OutlinedTextField

@Composable
fun RecommendationScreen(
    listId: Long,
    location: Pair<Double, Double>?,
    onBack: () -> Unit,
    onOpenPurchase: (String) -> Unit = {},
    purchaseViewModel: PurchaseViewModel = hiltViewModel(),
    productSearchViewModel: ProductSearchViewModel = hiltViewModel(),
    viewModel: RecommendationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val createdId by purchaseViewModel.createdId.collectAsStateWithLifecycle()
    val saving by purchaseViewModel.saving.collectAsStateWithLifecycle()
    val saveError by purchaseViewModel.message.collectAsStateWithLifecycle()
    val activePurchase by purchaseViewModel.activePurchase.collectAsStateWithLifecycle()
    val activeLoaded by purchaseViewModel.activeLoaded.collectAsStateWithLifecycle()
    val searchState by productSearchViewModel.uiState.collectAsStateWithLifecycle()
    var alternativeItem by remember { mutableStateOf<RecommendationItemDto?>(null) }
    var replacing by remember { mutableStateOf(false) }
    var replacementError by remember { mutableStateOf<UiText?>(null) }
    var similarFor by remember { mutableStateOf<RecommendationItemDto?>(null) }
    similarFor?.let { item ->
        // Predlog vrste se pokaže pre zamene: imena u katalogu su neujednačena.
        var kind by remember(item.itemId) { mutableStateOf(similarKind(item.requestedName, item.productBrand)) }
        AlertDialog(
            onDismissRequest = { similarFor = null },
            title = { Text(stringResource(R.string.rec_use_similar)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        stringResource(R.string.rec_similar_explanation, item.requestedName),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = kind,
                        onValueChange = { kind = it },
                        label = { Text(stringResource(R.string.rec_similar_kind_label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("similar-kind")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = kind.isNotBlank(),
                    onClick = {
                        similarFor = null
                        val (latitude, longitude) = requireNotNull(location)
                        viewModel.useSimilar(listId, item.itemId, kind, latitude, longitude)
                    },
                    modifier = Modifier.testTag("confirm-similar")
                ) { Text(stringResource(R.string.rec_replace)) }
            },
            dismissButton = { TextButton(onClick = { similarFor = null }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    LaunchedEffect(alternativeItem?.itemId) {
        alternativeItem?.let { item ->
            productSearchViewModel.clear()
            val query = try {
                viewModel.alternativeQuery(item.itemId)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                item.requestedName
            }
            productSearchViewModel.updateQuery(query)
        }
    }
    alternativeItem?.let { item ->
        AlternativePickerDialog(
            name = item.requestedName,
            state = searchState,
            saving = replacing,
            error = replacementError?.asString(),
            onQuery = productSearchViewModel::updateQuery,
            onRetry = productSearchViewModel::retry,
            onMore = productSearchViewModel::loadNextPage,
            onDismiss = {
                if (!replacing) {
                    alternativeItem = null
                    productSearchViewModel.clear()
                }
            },
            onSave = { product, packages ->
                replacing = true
                replacementError = null
                val position = requireNotNull(location)
                viewModel.replaceAlternative(
                    listId, item.itemId, product, packages, position.first, position.second
                ) { error ->
                    replacing = false
                    replacementError = error
                    if (error == null) {
                        alternativeItem = null
                        productSearchViewModel.clear()
                    }
                }
            }
        )
    }
    LaunchedEffect(listId) { purchaseViewModel.watchActive(listId) }
    LaunchedEffect(createdId) {
        createdId?.let { id ->
            onOpenPurchase(id)
            purchaseViewModel.consumeNavigation()
        }
    }
    LaunchedEffect(listId, location) {
        location?.let { (latitude, longitude) ->
            viewModel.load(listId, latitude, longitude)
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = stringResource(R.string.rec_title), onBack = onBack) },
        // The pinned action bar pads itself for the navigation bar, so the
        // content only needs the top and the sides.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            when {
                location == null -> ErrorState(
                    title = stringResource(R.string.rec_location_missing_title),
                    message = stringResource(R.string.rec_location_missing_message),
                    onRetry = onBack
                )
                state.isLoading -> LoadingState(stringResource(R.string.rec_loading))
                state.errorMessage != null -> ErrorState(
                    title = stringResource(R.string.rec_error_title),
                    message = requireNotNull(state.errorMessage).asString(),
                    onRetry = {
                        val (latitude, longitude) = requireNotNull(location)
                        viewModel.load(listId, latitude, longitude)
                    }
                )
                state.result != null -> RecommendationContent(
                    result = requireNotNull(state.result),
                    origin = requireNotNull(location),
                    saving = saving,
                    saveError = saveError?.asString(),
                    activePurchase = activePurchase,
                    activeLoaded = activeLoaded,
                    onResume = onOpenPurchase,
                    onAlternative = {
                        replacementError = null
                        alternativeItem = it
                    },
                    onStart = { scenario, createNew ->
                        purchaseViewModel.start(requireNotNull(state.result), scenario, createNew)
                    },
                    onChangeOrigin = onBack,
                    onSimilar = { item -> similarFor = item }
                )
            }
        }
    }
}

@Composable
internal fun RecommendationContent(
    result: ShoppingRecommendationDto,
    origin: Pair<Double, Double>,
    saving: Boolean,
    saveError: String?,
    activePurchase: PurchaseSession?,
    activeLoaded: Boolean,
    onResume: (String) -> Unit,
    onStart: (OptimizationScenarioDto, Boolean) -> Unit,
    onAlternative: (RecommendationItemDto) -> Unit = {},
    onChangeOrigin: () -> Unit = {},
    onSimilar: (RecommendationItemDto) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTypeName by rememberSaveable {
        mutableStateOf(RecommendationScenarioTypeDto.RECOMMENDED_BALANCE.name)
    }
    val scenarios = distinctScenarios(result)
    val selected = scenarios.firstOrNull { it.type.name == selectedTypeName }
        ?: result.recommendedBalance
    val unresolved = selected.items.filter {
        it.resultStatus != RecommendationItemStatusDto.AVAILABLE
    }
    val orderedStores = selected.stores.sortedBy { it.stopOrder }
    var confirmNew by rememberSaveable(result.listId, selected.type) { mutableStateOf(false) }
    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.rec_new_purchase_title)) },
            text = {
                Text(stringResource(R.string.rec_new_purchase_message))
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-new-purchase"),
                    enabled = !saving,
                    onClick = {
                        confirmNew = false
                        onStart(selected, true)
                    }
                ) { Text(stringResource(R.string.rec_new_purchase_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    val navigationBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("recommendation-list"),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.md,
                bottom = AppSpacing.xl + if (selected.available) 0.dp else navigationBarPadding
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            if (activePurchase != null) {
                item(key = "previous-purchase") {
                    PreviousPurchaseBanner(activePurchase, enabled = !saving, onResume = onResume)
                }
            }

            item(key = "origin") {
                OriginCard(origin, onChangeOrigin)
            }

            item(key = "scenario-chooser") {
                ScenarioChooser(
                    scenarios = scenarios,
                    selectedType = selected.type,
                    onSelect = { selectedTypeName = it.name }
                )
            }

            item(key = "scenario-hero") {
                ScenarioHeroCard(selected)
            }

            scenarioWarnings(selected, result).forEachIndexed { index, warning ->
                item(key = "warning-${selected.type}-$index") {
                    NoticeBanner(text = warning.asString(), tone = StatusTone.WARNING)
                }
            }

            saveError?.let { message ->
                item(key = "save-error") {
                    NoticeBanner(text = message, tone = StatusTone.ERROR)
                }
            }

            if (selected.available && orderedStores.isNotEmpty()) {
                item(key = "navigation-${selected.type}") {
                    RouteNavigationCard(
                        stopCount = orderedStores.size,
                        onClick = {
                            val url = googleMapsDirectionsUrl(
                                origin = Coordinates(origin.first, origin.second),
                                orderedStops = orderedStores.map { store ->
                                    Coordinates(store.latitude, store.longitude)
                                }
                            )
                            launchGoogleMapsDirections(context, url)
                        }
                    )
                }
            }

            orderedStores.forEach { store ->
                item(key = "store-${selected.type}-${store.storeId}") {
                    StoreSection(
                        store = store,
                        items = selected.items.filter { it.storeId == store.storeId }
                    )
                }
            }

            if (unresolved.isNotEmpty()) {
                item(key = "unresolved-${selected.type}") {
                    UnresolvedSection(unresolved, onAlternative, onSimilar)
                }
            }

            if (result.unlocatedPriceOptions.isNotEmpty()) {
                item(key = "unlocated") {
                    UnlocatedOptionsSection(result.unlocatedPriceOptions, selected)
                }
            }

            item(key = "calculation-details") {
                CalculationDetails(result, selected)
            }
        }

        if (selected.available) {
            BottomActionBar {
                PrimaryActionButton(
                    text = when {
                        saving -> stringResource(R.string.rec_saving_plan)
                        !activeLoaded -> stringResource(R.string.rec_checking_purchases)
                        else -> stringResource(R.string.rec_start_purchase)
                    },
                    enabled = !saving && activeLoaded,
                    modifier = Modifier.testTag("start-or-resume-purchase"),
                    onClick = {
                        if (activePurchase != null) confirmNew = true else onStart(selected, false)
                    }
                )
            }
        }
    }
}

@Composable
private fun PreviousPurchaseBanner(
    purchase: PurchaseSession,
    enabled: Boolean,
    onResume: (String) -> Unit
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                AppIcon(
                    R.drawable.ic_history,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(stringResource(R.string.rec_previous_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            R.string.rec_previous_progress,
                            date(purchase.snapshot.calculationDate),
                            purchase.purchasedCount,
                            purchase.snapshot.scenario.items.size
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            TonalActionButton(
                text = stringResource(R.string.rec_resume_previous),
                enabled = enabled,
                onClick = { onResume(purchase.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("resume-previous-purchase")
            )
        }
    }
}

@Composable
internal fun RouteNavigationCard(
    stopCount: Int,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        TonalActionButton(
            text = if (stopCount == 1) {
                stringResource(R.string.rec_route_single)
            } else {
                pluralStringResource(R.plurals.rec_route_stores, stopCount, stopCount)
            },
            icon = R.drawable.ic_directions,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open-google-maps")
        )
        Text(
            stringResource(R.string.rec_route_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AppSpacing.sm)
        )
    }
}

@Composable
private fun ScenarioChooser(
    scenarios: List<OptimizationScenarioDto>,
    selectedType: RecommendationScenarioTypeDto,
    onSelect: (RecommendationScenarioTypeDto) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
    ) {
        scenarios.forEach { scenario ->
            val isSelected = scenario.type == selectedType
            // The three totals sit side by side so the choice is a comparison
            // of prices, not of paragraphs.
            val weight by animateFloatAsState(
                targetValue = if (isSelected) 1.25f else 1f,
                animationSpec = spring(dampingRatio = 0.6f),
                label = "scenarioWeight"
            )
            Surface(
                onClick = { onSelect(scenario.type) },
                enabled = scenario.available,
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                },
                border = if (isSelected) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    cardBorder
                },
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .testTag("scenario-${scenario.type.name}")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Two lines for every label, so a label that wraps does not
                    // push its price below the others.
                    // Sa krupnim slovima se naziv i cena smanjuju, umesto da se
                    // lome usred reči („Najjeftin/ija", „1.50/1").
                    // Svaka reč u svom redu: reč koja ne stane pravi treći red,
                    // a to je prekoračenje, pa se slova smanje.
                    BasicText(
                        stringResource(scenarioShortTitle(scenario.type)).replace(' ', '\n'),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        minLines = 2,
                        maxLines = 2,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 8.sp,
                            maxFontSize = MaterialTheme.typography.labelMedium.fontSize
                        )
                    )
                    BasicText(
                        scenario.totalCost?.takeIf { scenario.available }?.let(::wholeDinars)
                            ?: stringResource(R.string.rec_no_price_short),
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 10.sp,
                            maxFontSize = MaterialTheme.typography.titleLarge.fontSize
                        )
                    )
                    // Says which number this is, so the cheapest basket
                    // showing the highest figure reads as sense, not error.
                    Text(
                        stringResource(R.string.rec_total_caption),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scenario.available) {
                        Text(
                            stringResource(R.string.rec_basket_short, wholeDinars(scenario.basketCost)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** Polazna tačka plana; „Promeni" vraća na izbor lokacije. */
@Composable
private fun OriginCard(origin: Pair<Double, Double>, onChange: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_my_location, contentDescription = null)
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.rec_origin_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    coordinatesLabel(origin.first, origin.second),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TonalActionButton(text = stringResource(R.string.rec_change_origin), onClick = onChange)
        }
    }
}

@Composable
private fun ScenarioHeroCard(scenario: OptimizationScenarioDto) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Tamna traka na vrhu, kao na nacrtu: ovo je plan o kome se radi.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Column(
                modifier = Modifier.padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                // Oznake, naslov i cena jedno ispod drugog: jedno pored drugog
                // su se sa krupnim slovima lomili po slovima („Prepor/učeni").
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    StatusPill(stringResource(scenarioBadge(scenario.type)), StatusTone.POSITIVE)
                    if (scenario.available) {
                        scenario.savingsComparedWithSingleStore?.takeIf { it > 0.0 }?.let { savings ->
                            StatusPill(stringResource(R.string.rec_savings, wholeDinars(savings)), StatusTone.POSITIVE)
                        }
                    }
                }
                Text(scenarioTitle(scenario.type), style = MaterialTheme.typography.titleLarge)
                if (scenario.available) {
                    // The number the screen exists for, at a size that needs
                    // no searching for.
                    Text(
                        scenario.totalCost?.let(::money) ?: stringResource(R.string.rec_no_price),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (scenario.available) {
                    // Put i vreme is everything above the basket, so the two
                    // parts always add up to the total shown above them.
                    scenario.totalCost?.let {
                        Text(
                            stringResource(
                                R.string.rec_basket_plus_travel,
                                money(scenario.basketCost),
                                money(it - scenario.basketCost)
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                            .padding(vertical = AppSpacing.md)
                    ) {
                        PlanStat(stringResource(R.string.rec_stat_stores), scenario.stopCount.toString(), Modifier.weight(1f))
                        PlanStat(stringResource(R.string.rec_stat_distance), distance(scenario.routeDistanceKm), Modifier.weight(1f))
                        PlanStat(stringResource(R.string.rec_stat_time), "~${duration(scenario.routeDurationSeconds)}", Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            R.drawable.ic_check_circle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(
                            stringResource(R.string.rec_coverage),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.rec_covered_of,
                                scenario.items.size,
                                scenario.coveredItems,
                                scenario.items.size
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(scenario.explanation, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PlanStat(label: String, value: String, modifier: Modifier) {
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
        Text(value, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StoreSection(
    store: RecommendationStoreDto,
    items: List<RecommendationItemDto>
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.padding(AppSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                StopBadge(store.stopOrder)
                Column(modifier = Modifier.weight(1f)) {
                    Text(store.retailerName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        storeAddress(store),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(
                            R.string.rec_store_leg,
                            distance(store.distanceFromPreviousKm),
                            duration(store.durationFromPreviousSeconds)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    money(items.sumOf { it.lineTotal ?: 0.0 }),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.rec_store_no_items),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(AppSpacing.lg)
                )
            }
            items.forEachIndexed { index, item ->
                PlanItemRow(item)
                if (index < items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = AppSpacing.lg),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun StopBadge(number: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Box(modifier = Modifier.size(30.dp), contentAlignment = Alignment.Center) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** What was asked for, what it became, and the line's price on the right. */
@Composable
private fun PlanItemRow(item: RecommendationItemDto) {
    Row(
        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(item.requestedName, style = MaterialTheme.typography.titleMedium)
            item.productName
                ?.takeUnless { it.equals(item.requestedName, ignoreCase = true) }
                ?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            itemQuantityLine(item)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (manySmallPacks(item)) {
                StatusPill(stringResource(R.string.rec_many_small_packs), StatusTone.WARNING)
            }
        }
        Text(
            item.lineTotal?.let(::money) ?: stringResource(R.string.rec_no_price_short),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = AppSpacing.sm)
        )
    }
}

@Composable
private fun UnresolvedSection(
    items: List<RecommendationItemDto>,
    onAlternative: (RecommendationItemDto) -> Unit,
    onSimilar: (RecommendationItemDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SectionHeader(stringResource(R.string.rec_unresolved_header), trailing = items.size.toString())
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                items.forEachIndexed { index, item ->
                    Column(
                        modifier = Modifier.padding(
                            start = AppSpacing.lg,
                            end = AppSpacing.sm,
                            top = AppSpacing.md
                        )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                item.requestedName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            StatusPill(stringResource(unresolvedStatus(item.resultStatus)), StatusTone.WARNING)
                        }
                        Text(
                            item.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = AppSpacing.sm)
                        )
                        // Tvoja ideja: „ovog nema ovde, evo sličnog". Slično
                        // (bilo koji brend) se bira samo, a zamene su za ručni izbor.
                        Row {
                            if (item.matchingRule != ShoppingItemRuleDto.FLEXIBLE_CATEGORY) {
                                TextButton(
                                    onClick = { onSimilar(item) },
                                    modifier = Modifier.testTag("use-similar-${item.itemId}")
                                ) { Text(stringResource(R.string.rec_use_similar)) }
                            }
                            TextButton(onClick = { onAlternative(item) }) {
                                Text(stringResource(R.string.rec_view_alternatives))
                            }
                        }
                    }
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/**
 * Chains we have prices for but cannot place on a map. They stay out of the
 * plan, since a shop nobody can find is not a recommendation, but hiding a
 * cheaper basket would be worse.
 */
@Composable
internal fun UnlocatedOptionsSection(
    options: List<UnlocatedPriceOptionDto>,
    selected: OptimizationScenarioDto
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SectionHeader(stringResource(R.string.rec_unlocated_header))
        NoticeBanner(
            title = stringResource(R.string.rec_unlocated_notice_title),
            text = stringResource(R.string.rec_unlocated_notice_text),
            tone = StatusTone.WARNING
        )
        if (selected.available) {
            Text(
                stringResource(R.string.rec_unlocated_selected_basket, money(selected.basketCost)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                options.forEachIndexed { index, option ->
                    val cheaper = selected.available &&
                        option.coveredItems == option.totalItems &&
                        option.lowestBasketCost < selected.basketCost
                    Column(
                        modifier = Modifier.padding(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                option.retailerName,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(basketRange(option), style = MaterialTheme.typography.titleMedium)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Text(
                                pluralStringResource(
                                    R.plurals.rec_covered_of,
                                    option.totalItems,
                                    option.coveredItems,
                                    option.totalItems
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            StatusPill(stringResource(R.string.rec_unconfirmed_store), StatusTone.WARNING)
                            if (cheaper) {
                                StatusPill(stringResource(R.string.rec_cheaper_basket), StatusTone.POSITIVE)
                            }
                        }
                        Text(
                            option.caveat,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (index < options.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** Everything behind the numbers, folded away until someone asks. */
@Composable
private fun CalculationDetails(
    result: ShoppingRecommendationDto,
    scenario: OptimizationScenarioDto
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val assumptions = result.assumptions
    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier.padding(AppSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                AppIcon(R.drawable.ic_info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.rec_calc_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                AppIcon(
                    if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                    contentDescription = null
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(AppSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    listOfNotNull(
                        // date() already ends with the ordinal full stop.
                        scenario.dataAsOf?.let { stringResource(R.string.rec_calc_data_as_of, date(it)) }
                            ?: stringResource(R.string.rec_calc_no_prices),
                        scenario.disclaimer.ifBlank { result.disclaimer },
                        scenario.takeIf { it.available }?.let {
                            stringResource(
                                R.string.rec_calc_costs,
                                money(it.basketCost),
                                money(it.travelCost),
                                money(it.timeCost),
                                money(it.stopCost)
                            )
                        },
                        stringResource(
                            R.string.rec_calc_rates,
                            money(assumptions.costPerKm),
                            money(assumptions.valuePerHour),
                            money(assumptions.costPerStop)
                        ),
                        if (scenario.approximateRoute) {
                            stringResource(R.string.rec_calc_approximate)
                        } else {
                            null
                        },
                        pluralStringResource(
                            R.plurals.rec_calc_candidates,
                            result.candidateStoreCount,
                            result.candidateStoreCount,
                            decimal(assumptions.candidateRadiusMeters / 1000.0, 1)
                        ),
                        pluralStringResource(
                            R.plurals.rec_calc_max_age,
                            assumptions.maxPriceAgeDays,
                            assumptions.maxPriceAgeDays
                        ),
                        stringResource(
                            R.string.rec_calc_sources,
                            priceSourceNames(scenario, stringResource(R.string.rec_calc_sources_none))
                        )
                    ).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun scenarioWarnings(
    scenario: OptimizationScenarioDto,
    result: ShoppingRecommendationDto
): List<UiText> = buildList {
    if (!scenario.available) return@buildList
    val missing = scenario.items.size - scenario.coveredItems
    if (!scenario.complete && missing > 0) {
        // Not recognising an item and a shop not selling it ask for different
        // things: editing the item, or another shop.
        val unrecognised = scenario.unmatchedItems.coerceAtMost(missing)
        val unpriced = missing - unrecognised
        if (unrecognised > 0) {
            add(uiPlural(R.plurals.rec_warning_unrecognised, unrecognised))
        }
        if (unpriced > 0) {
            add(uiPlural(R.plurals.rec_warning_unpriced, unpriced))
        }
    }
    // "Šećer 25 kg" as fifty half-kilo bags is cheaper but rarely what anyone
    // wants to carry, so the plan says so instead of hiding it.
    val smallPacks = scenario.items.filter(::manySmallPacks).map { it.requestedName }
    if (smallPacks.isNotEmpty()) {
        add(uiText(R.string.rec_warning_small_packs, smallPacks.joinToString(", ")))
    }
    val asOf = scenario.dataAsOf
    if (asOf != null && asOf != result.requestedDate) {
        add(uiText(R.string.rec_warning_old_prices, date(asOf)))
    }
}

internal fun storeAddress(store: RecommendationStoreDto): String =
    listOfNotNull(
        store.storeName,
        store.address?.takeUnless { store.storeName.contains(it, ignoreCase = true) },
        store.city?.takeUnless { store.storeName.contains(it, ignoreCase = true) }
    ).joinToString(", ")

private fun basketRange(option: UnlocatedPriceOptionDto): String =
    if (abs(option.highestBasketCost - option.lowestBasketCost) < 0.005) {
        money(option.lowestBasketCost)
    } else {
        money(option.lowestBasketCost).removeSuffix(" RSD") + " – " + money(option.highestBasketCost)
    }

private fun priceSourceNames(scenario: OptimizationScenarioDto, none: String): String =
    scenario.priceSources
        .map { code ->
            scenario.stores.firstOrNull { it.retailerCode == code }?.retailerName
                ?: scenario.items.firstOrNull { it.retailerCode == code }?.retailerName
                ?: code.replace('_', ' ')
        }
        .distinct()
        .joinToString()
        .ifBlank { none }

@StringRes
private fun unresolvedStatus(status: RecommendationItemStatusDto): Int = when (status) {
    RecommendationItemStatusDto.NEEDS_CONFIRMATION -> R.string.rec_status_needs_confirmation
    RecommendationItemStatusDto.UNMATCHED -> R.string.rec_status_unmatched
    RecommendationItemStatusDto.NO_VALID_PRICE -> R.string.rec_status_no_price
    RecommendationItemStatusDto.AVAILABLE -> R.string.rec_status_available
}

/** Oznaka na kartici plana, kao „PREPORUČENO" na nacrtu. */
@StringRes
private fun scenarioBadge(type: RecommendationScenarioTypeDto): Int = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> R.string.rec_badge_single_store
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> R.string.rec_badge_balance
    RecommendationScenarioTypeDto.LOWEST_PRICE -> R.string.rec_badge_lowest_price
}

@StringRes
internal fun scenarioTitleRes(type: RecommendationScenarioTypeDto): Int = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> R.string.rec_scenario_single_store
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> R.string.rec_scenario_balance
    RecommendationScenarioTypeDto.LOWEST_PRICE -> R.string.rec_scenario_lowest_price
}

@Composable
internal fun scenarioTitle(type: RecommendationScenarioTypeDto): String =
    stringResource(scenarioTitleRes(type))

/**
 * Each scenario wins at a different thing, so the label has to say which.
 * "Najjeftinije" alone read as a promise about the total, and the cheapest
 * basket can carry the dearest journey.
 */
@StringRes
internal fun scenarioShortTitle(type: RecommendationScenarioTypeDto): Int = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> R.string.rec_short_single_store
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> R.string.rec_short_balance
    RecommendationScenarioTypeDto.LOWEST_PRICE -> R.string.rec_short_lowest_price
}

/**
 * Ten or more packs make up an amount the shopper wrote ("šećer 25 kg" as 50 ×
 * 500 g). Not a count they asked for themselves, and not bottles or cans
 * asked for by the piece.
 */
internal fun manySmallPacks(item: RecommendationItemDto): Boolean {
    val quantity = item.purchaseQuantity ?: return false
    val packages = quantity.packages ?: return false
    return packages >= 10.0 &&
        quantity.baseUnit != null && quantity.baseUnit != "piece" &&
        packages != item.requestedQuantity
}

/**
 * The same plan offered twice reads as a choice that is not one: on the slava
 * list the best overall plan was also the cheapest basket, and on a short list
 * the cheapest basket was the one store. Three boxes showed two plans. The
 * best overall plan always stays; a copy of a plan already shown goes.
 */
internal fun distinctScenarios(result: ShoppingRecommendationDto): List<OptimizationScenarioDto> {
    fun samePlan(one: OptimizationScenarioDto, other: OptimizationScenarioDto): Boolean =
        one.available == other.available &&
            one.basketCost == other.basketCost &&
            one.stores.map { it.storeId }.toSet() == other.stores.map { it.storeId }.toSet()
    val best = result.recommendedBalance
    val single = result.singleStore.takeUnless { samePlan(it, best) }
    val lowest = result.lowestPrice.takeUnless { lowest ->
        samePlan(lowest, best) || (single != null && samePlan(lowest, single))
    }
    return listOfNotNull(single, best, lowest)
}

internal fun itemPriceBreakdown(item: RecommendationItemDto): String? {
    val unitPrice = item.effectivePrice ?: return null
    val quantity = decimal(item.purchaseQuantity?.packages ?: item.requestedQuantity)
    return "$quantity × ${money(unitPrice)}"
}

/**
 * One quiet line under a product: how many packs when more than one, the
 * price per kilo or litre for comparing, and any surplus from whole packs.
 */
@Composable
internal fun itemQuantityLine(item: RecommendationItemDto): String? {
    val quantity = item.purchaseQuantity
    val packages = quantity?.packages ?: item.requestedQuantity
    return listOfNotNull(
        itemPriceBreakdown(item)?.takeIf { BigDecimal.valueOf(packages).compareTo(BigDecimal.ONE) != 0 },
        quantity?.unitPrice?.let { price ->
            quantity.baseUnit?.let { "${money(price)}/${perUnit(it)}" }
        },
        quantity?.let { surplus(it) }
    ).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun surplus(quantity: PurchaseQuantityDto): String? {
    val unit = quantity.baseUnit ?: return null
    val extra = quantity.extraAmount?.takeIf { it > 0 } ?: return null
    val supplied = quantity.suppliedAmount ?: return null
    return stringResource(R.string.rec_surplus, amountLabel(supplied, unit), amountLabel(extra, unit))
}

private fun perUnit(baseUnit: String): String = when (baseUnit) {
    "g" -> "kg"
    "ml" -> "l"
    else -> "kom"
}
