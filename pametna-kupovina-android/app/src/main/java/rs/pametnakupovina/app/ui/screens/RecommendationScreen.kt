package rs.pametnakupovina.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import rs.pametnakupovina.app.data.network.RecommendationItemStatusDto
import rs.pametnakupovina.app.data.network.RecommendationScenarioTypeDto
import rs.pametnakupovina.app.data.network.RecommendationStoreDto
import rs.pametnakupovina.app.data.network.ShoppingRecommendationDto
import rs.pametnakupovina.app.data.network.UnlocatedPriceOptionDto
import rs.pametnakupovina.app.data.purchase.PurchaseSession
import rs.pametnakupovina.app.navigation.NavigationPoint
import rs.pametnakupovina.app.navigation.googleMapsDirectionsUrl
import rs.pametnakupovina.app.navigation.launchGoogleMapsDirections
import rs.pametnakupovina.app.ui.ProductSearchViewModel
import rs.pametnakupovina.app.ui.PurchaseViewModel
import rs.pametnakupovina.app.ui.RecommendationViewModel
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
import rs.pametnakupovina.app.ui.date
import rs.pametnakupovina.app.ui.decimal
import rs.pametnakupovina.app.ui.distance
import rs.pametnakupovina.app.ui.duration
import rs.pametnakupovina.app.ui.money
import rs.pametnakupovina.app.ui.plural
import rs.pametnakupovina.app.ui.wholeDinars

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
    var replacementError by remember { mutableStateOf<String?>(null) }

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
            error = replacementError,
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
        topBar = { AppTopBar(title = "Preporuke", onBack = onBack) },
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
                    title = "Lokacija više nije dostupna",
                    message = "Vrati se i ponovo izaberi polaznu tačku.",
                    onRetry = onBack
                )
                state.isLoading -> LoadingState("Računam tri scenarija…")
                state.errorMessage != null -> ErrorState(
                    title = "Rezultat nije dostupan",
                    message = requireNotNull(state.errorMessage),
                    onRetry = {
                        val (latitude, longitude) = requireNotNull(location)
                        viewModel.load(listId, latitude, longitude)
                    }
                )
                state.result != null -> RecommendationContent(
                    result = requireNotNull(state.result),
                    origin = requireNotNull(location),
                    saving = saving,
                    saveError = saveError,
                    activePurchase = activePurchase,
                    activeLoaded = activeLoaded,
                    onResume = onOpenPurchase,
                    onAlternative = {
                        replacementError = null
                        alternativeItem = it
                    },
                    onStart = { scenario, createNew ->
                        purchaseViewModel.start(requireNotNull(state.result), scenario, createNew)
                    }
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
    onAlternative: (RecommendationItemDto) -> Unit = {}
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
            title = { Text("Započni novu kupovinu?") },
            text = {
                Text(
                    "Novi plan počinje sa praznim kućicama. Prethodna kupovina i " +
                        "čekirane stavke ostaju u „Kupovine“."
                )
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-new-purchase"),
                    enabled = !saving,
                    onClick = {
                        confirmNew = false
                        onStart(selected, true)
                    }
                ) { Text("Započni novu kupovinu") }
            },
            dismissButton = {
                TextButton(onClick = { confirmNew = false }) { Text("Otkaži") }
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
                    NoticeBanner(text = warning, tone = StatusTone.WARNING)
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
                                origin = NavigationPoint(origin.first, origin.second),
                                orderedStops = orderedStores.map { store ->
                                    NavigationPoint(store.latitude, store.longitude)
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
                    UnresolvedSection(unresolved, onAlternative)
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
                        saving -> "Čuvam plan…"
                        !activeLoaded -> "Proveravam sačuvane kupovine…"
                        else -> "Započni kupovinu po ovom planu"
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
                    Text("Već imaš započetu kupovinu", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Plan od ${date(purchase.snapshot.calculationDate)} · " +
                            "kupljeno ${purchase.purchasedCount} od ${purchase.snapshot.scenario.items.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            FilledTonalButton(
                enabled = enabled,
                onClick = { onResume(purchase.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("resume-previous-purchase")
            ) {
                Text("Nastavi prethodnu kupovinu")
            }
        }
    }
}

@Composable
internal fun RouteNavigationCard(
    stopCount: Int,
    onClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .testTag("open-google-maps")
        ) {
            AppIcon(R.drawable.ic_directions, contentDescription = null)
            Spacer(Modifier.width(AppSpacing.sm))
            Text(
                if (stopCount == 1) {
                    "Pregled puta do prodavnice"
                } else {
                    "Pregled rute kroz $stopCount ${plural(stopCount, "prodavnicu", "prodavnice", "prodavnica")}"
                }
            )
        }
        Text(
            "Google Maps prvo prikaže rutu, a navigaciju pokrećeš ti.",
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
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                border = if (isSelected) {
                    BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                } else {
                    null
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
                    Text(
                        scenarioShortTitle(scenario.type),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        minLines = 2,
                        maxLines = 2
                    )
                    Text(
                        scenario.totalCost?.takeIf { scenario.available }?.let(::wholeDinars) ?: "nema",
                        style = MaterialTheme.typography.titleLarge
                    )
                    // Says which number this is, so the cheapest basket
                    // showing the highest figure reads as sense, not error.
                    Text(
                        "ukupno",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (scenario.available) {
                        Text(
                            "korpa ${wholeDinars(scenario.basketCost)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioHeroCard(scenario: OptimizationScenarioDto) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                scenarioTitle(scenario.type),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (scenario.available) {
                val total = scenario.totalCost
                // The number the screen exists for, at a size that needs no
                // searching for.
                Text(
                    total?.let(::money) ?: "nema cene",
                    style = MaterialTheme.typography.displaySmall
                )
                // Put i vreme is everything above the basket, so the two
                // parts always add up to the total shown above them.
                total?.let {
                    Text(
                        "korpa ${money(scenario.basketCost)} + put i vreme ${money(it - scenario.basketCost)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    listOf(
                        "${scenario.coveredItems} od ${scenario.items.size} " +
                            plural(scenario.items.size, "stavke", "stavke", "stavki"),
                        counted(scenario.stopCount, "stajanje", "stajanja", "stajanja"),
                        distance(scenario.routeDistanceKm)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium
                )
                scenario.savingsComparedWithSingleStore?.takeIf { it > 0.0 }?.let { savings ->
                    Text(
                        "Jeftinije od jedne prodavnice za ${money(savings)}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Text(scenario.explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
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
                        "${distance(store.distanceFromPreviousKm)} · oko ${duration(store.durationFromPreviousSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    money(items.sumOf { it.lineTotal ?: 0.0 }),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (items.isEmpty()) {
                Text(
                    "Nema dodeljenih stavki.",
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
                StatusPill("Mnogo malih pakovanja", StatusTone.WARNING)
            }
        }
        Text(
            item.lineTotal?.let(::money) ?: "nema",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = AppSpacing.sm)
        )
    }
}

@Composable
private fun UnresolvedSection(
    items: List<RecommendationItemDto>,
    onAlternative: (RecommendationItemDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SectionHeader("Bez ponude", trailing = items.size.toString())
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
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
                            StatusPill(unresolvedStatus(item.resultStatus), StatusTone.WARNING)
                        }
                        Text(
                            item.explanation,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = AppSpacing.sm)
                        )
                        TextButton(onClick = { onAlternative(item) }) {
                            Text("Pogledaj zamene")
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
private fun UnlocatedOptionsSection(
    options: List<UnlocatedPriceOptionDto>,
    selected: OptimizationScenarioDto
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        SectionHeader("Lanci bez poznate adrese")
        Text(
            "Objavljuju cene, ali ne i gde su im prodavnice, pa ne ulaze u plan." +
                if (selected.available) " Korpa u izabranom planu: ${money(selected.basketCost)}." else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
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
                                "${option.coveredItems} od ${option.totalItems} " +
                                    plural(option.totalItems, "stavke", "stavke", "stavki"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (cheaper) {
                                StatusPill("Jeftinija korpa", StatusTone.POSITIVE)
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
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text("Kako je računato")
            AppIcon(
                if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                contentDescription = null
            )
        }
        if (expanded) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    listOfNotNull(
                        // date() already ends with the ordinal full stop.
                        scenario.dataAsOf?.let { "Cene su iz cenovnika od ${date(it)}" }
                            ?: "Za ovaj scenario nema važećih cena.",
                        scenario.disclaimer.ifBlank { result.disclaimer },
                        scenario.takeIf { it.available }?.let {
                            "Korpa ${money(it.basketCost)}, put ${money(it.travelCost)}, " +
                                "vreme ${money(it.timeCost)}, stajanja ${money(it.stopCost)}."
                        },
                        "Put računamo ${money(assumptions.costPerKm)} po kilometru, vreme " +
                            "${money(assumptions.valuePerHour)} po satu i ${money(assumptions.costPerStop)} po stajanju.",
                        if (scenario.approximateRoute) {
                            "Udaljenosti su procena vazdušnom linijom, pravi put je obično duži."
                        } else {
                            null
                        },
                        "Razmotreno ${counted(result.candidateStoreCount, "prodavnica", "prodavnice", "prodavnica")} " +
                            "u krugu od ${decimal(assumptions.candidateRadiusMeters / 1000.0, 1)} km.",
                        "Prednost imaju cene ne starije od " +
                            counted(assumptions.maxPriceAgeDays, "dan", "dana", "dana") + ".",
                        "Izvori cena: ${priceSourceNames(scenario)}."
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
): List<String> = buildList {
    if (!scenario.available) return@buildList
    val missing = scenario.items.size - scenario.coveredItems
    if (!scenario.complete && missing > 0) {
        // Not recognising an item and a shop not selling it ask for different
        // things: editing the item, or another shop.
        val unrecognised = scenario.unmatchedItems.coerceAtMost(missing)
        val unpriced = missing - unrecognised
        if (unrecognised > 0) {
            add(
                "Ne prepoznajemo " +
                    counted(unrecognised, "stavku", "stavke", "stavki") + ", pa " +
                    plural(unrecognised, "nije", "nisu", "nisu") + " u računu."
            )
        }
        if (unpriced > 0) {
            add(
                "Plan je nepotpun: " +
                    counted(unpriced, "stavka nema", "stavke nemaju", "stavki nema") +
                    " ponudu u ovim prodavnicama."
            )
        }
    }
    // "Šećer 25 kg" as fifty half-kilo bags is cheaper but rarely what anyone
    // wants to carry, so the plan says so instead of hiding it.
    val smallPacks = scenario.items.filter(::manySmallPacks).map { it.requestedName }
    if (smallPacks.isNotEmpty()) {
        add(
            "Od mnogo malih pakovanja: ${smallPacks.joinToString(", ")}. " +
                "Proveri da li ti tako odgovara ili izaberi veće pakovanje."
        )
    }
    val asOf = scenario.dataAsOf
    if (asOf != null && asOf != result.requestedDate) {
        add("Cene su iz cenovnika od ${date(asOf)}, ne od danas. Proveri ih pre kupovine.")
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

private fun priceSourceNames(scenario: OptimizationScenarioDto): String =
    scenario.priceSources
        .map { code ->
            scenario.stores.firstOrNull { it.retailerCode == code }?.retailerName
                ?: scenario.items.firstOrNull { it.retailerCode == code }?.retailerName
                ?: code.replace('_', ' ')
        }
        .distinct()
        .joinToString()
        .ifBlank { "nisu navedeni" }

private fun unresolvedStatus(status: RecommendationItemStatusDto): String = when (status) {
    RecommendationItemStatusDto.NEEDS_CONFIRMATION -> "Treba potvrda"
    RecommendationItemStatusDto.UNMATCHED -> "Nije pronađeno"
    RecommendationItemStatusDto.NO_VALID_PRICE -> "Nema cene"
    RecommendationItemStatusDto.AVAILABLE -> "Dostupno"
}

internal fun scenarioTitle(type: RecommendationScenarioTypeDto): String = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> "Jedna prodavnica"
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> "Preporučeni balans"
    RecommendationScenarioTypeDto.LOWEST_PRICE -> "Najniža cena"
}

/**
 * Each scenario wins at a different thing, so the label has to say which.
 * "Najjeftinije" alone read as a promise about the total, and the cheapest
 * basket can carry the dearest journey.
 */
internal fun scenarioShortTitle(type: RecommendationScenarioTypeDto): String = when (type) {
    RecommendationScenarioTypeDto.SINGLE_STORE -> "Jedna stanica"
    RecommendationScenarioTypeDto.RECOMMENDED_BALANCE -> "Najbolje ukupno"
    RecommendationScenarioTypeDto.LOWEST_PRICE -> "Najjeftinija korpa"
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
internal fun itemQuantityLine(item: RecommendationItemDto): String? {
    val quantity = item.purchaseQuantity
    val packages = quantity?.packages ?: item.requestedQuantity
    return listOfNotNull(
        itemPriceBreakdown(item)?.takeIf { BigDecimal.valueOf(packages).compareTo(BigDecimal.ONE) != 0 },
        quantity?.unitPrice?.let { price ->
            quantity.baseUnit?.let { "${money(price)}/${perUnit(it)}" }
        },
        quantity?.let(::surplus)
    ).joinToString(" · ").ifEmpty { null }
}

private fun surplus(quantity: PurchaseQuantityDto): String? {
    val unit = quantity.baseUnit ?: return null
    val extra = quantity.extraAmount?.takeIf { it > 0 } ?: return null
    val supplied = quantity.suppliedAmount ?: return null
    return "dobijaš ${amountLabel(supplied, unit)}, ${amountLabel(extra, unit)} više"
}

private fun perUnit(baseUnit: String): String = when (baseUnit) {
    "g" -> "kg"
    "ml" -> "l"
    else -> "kom"
}
