package rs.pametnakupovina.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import rs.pametnakupovina.app.ui.components.AppBackdrop
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.ChoiceChip
import rs.pametnakupovina.app.ui.components.GlassCard
import rs.pametnakupovina.app.ui.components.IconTile
import rs.pametnakupovina.app.ui.components.pressScale
import rs.pametnakupovina.app.ui.components.LocalBottomBarInset
import rs.pametnakupovina.app.ui.theme.LocalAppColors
import rs.pametnakupovina.app.ui.theme.ThemeChoice
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.R
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.screens.AboutDialog
import rs.pametnakupovina.app.ui.screens.HouseholdDialog
import rs.pametnakupovina.app.ui.screens.TripCostDialog
import rs.pametnakupovina.app.ui.screens.DashboardScreen
import rs.pametnakupovina.app.ui.screens.LocationScreen
import rs.pametnakupovina.app.ui.screens.LoyaltyCardsScreen
import rs.pametnakupovina.app.ui.screens.MatchingScreen
import rs.pametnakupovina.app.ui.screens.ProductDetailsScreen
import rs.pametnakupovina.app.ui.screens.RecommendationScreen
import rs.pametnakupovina.app.ui.screens.ShoppingListScreen
import rs.pametnakupovina.app.ui.screens.PurchaseScreen
import rs.pametnakupovina.app.ui.screens.RECEIPT_FILE_TYPES
import rs.pametnakupovina.app.ui.screens.SalesScreen
import androidx.compose.ui.platform.LocalDensity

private object Route {
    const val DASHBOARD = "dashboard"
    const val LIST = "list"
    const val MATCHING = "matching/{listId}"
    const val LOCATION = "location/{listId}"
    const val RECOMMENDATION = "recommendation/{listId}"
    const val PRODUCT_DETAILS = "product/{canonicalProductId}"
    const val CARDS = "cards"
    const val HISTORY = "purchases"
    const val SALES = "sales"

    fun matching(listId: Long) = "matching/$listId"
    fun location(listId: Long) = "location/$listId"
    fun recommendation(listId: Long) = "recommendation/$listId"
    fun productDetails(canonicalProductId: Long) =
        "product/$canonicalProductId"
}

/** Tabovi donje trake; ostali ekrani su koraci u toku i traku skrivaju. */
private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME(Route.DASHBOARD, R.string.app_tab_home, R.drawable.ic_home),
    LIST(Route.LIST, R.string.app_tab_list, R.drawable.ic_content_paste),
    CARDS(Route.CARDS, R.string.app_tab_cards, R.drawable.ic_card),
    HISTORY(Route.HISTORY, R.string.app_tab_history, R.drawable.ic_history)
}

/** Kao tab: jedan primerak svakog taba, a svaki pamti dokle se stiglo u njemu. */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PametnaKupovinaApp(
    sharedReceipt: Uri? = null,
    onSharedReceiptTaken: () -> Unit = {},
    openedProduct: Long? = null,
    onOpenedProductTaken: () -> Unit = {},
    theme: ThemeChoice = ThemeChoice.SYSTEM,
    onTheme: (ThemeChoice) -> Unit = {}
) {
    val navController = rememberNavController()
    val calculationSession: CalculationSessionViewModel = hiltViewModel()
    val calculationLocation by calculationSession.location
        .collectAsStateWithLifecycle()
    val calculationOriginLabel by calculationSession.originLabel
        .collectAsStateWithLifecycle()
    // Deljen sa dashboard-om i menijem, da skeniranje iz menija odmah osveži
    // brojke koje dashboard već prikazuje.
    val receiptViewModel: ReceiptViewModel = hiltViewModel()

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showHousehold by rememberSaveable { mutableStateOf(false) }
    var showTripCosts by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Ishod učitavanja računa se javlja na početnom ekranu, kao i skeniranje.
    val pickReceiptFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            navController.openTab(Route.DASHBOARD)
            receiptViewModel.importFile(uri)
        }
    }
    LaunchedEffect(openedProduct) {
        openedProduct?.let {
            onOpenedProductTaken()
            navController.navigate(Route.productDetails(it))
        }
    }
    LaunchedEffect(sharedReceipt) {
        sharedReceipt?.let {
            onSharedReceiptTaken()
            navController.openTab(Route.DASHBOARD)
            receiptViewModel.importFile(it)
        }
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenLink = { url ->
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            }
        )
    }

    if (showHousehold) {
        HouseholdDialog(
            onDismiss = { showHousehold = false },
            onJoined = receiptViewModel::refresh
        )
    }

    if (showTripCosts) {
        TripCostDialog(onDismiss = { showTripCosts = false })
    }

    BackHandler(enabled = showMenu) { showMenu = false }
    val menuContent: @Composable () -> Unit = {
            AppMenuContent(
                theme = theme,
                onTheme = onTheme,
                onSales = {
                    showMenu = false
                    navController.navigate(Route.SALES)
                },
                onScan = {
                    showMenu = false
                    // Ishod skeniranja se javlja na početnom ekranu.
                    navController.openTab(Route.DASHBOARD)
                    receiptViewModel.scan(context)
                },
                onImportFile = {
                    showMenu = false
                    pickReceiptFile.launch(RECEIPT_FILE_TYPES)
                },
                onHousehold = {
                    showMenu = false
                    showHousehold = true
                },
                onTripCosts = {
                    showMenu = false
                    showTripCosts = true
                },
                onAbout = {
                    showMenu = false
                    showAbout = true
                }
            )
    }

    val onTab = Tab.entries.any { it.route == currentRoute }
    var barHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Sadržaj tabova skroluje ispod lebdeće trake, pa sam ostavlja mesto za nju.
    val barInset = if (onTab) barHeight + systemBottom + NavBarMargin * 2 else 0.dp

    // Traka zamućuje ono što prolazi ispod nje (Android 12+); starije verzije
    // dobijaju skoro neprovidnu traku kao ranije.
    val haze = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(haze)) {
        AppBackdrop()
        CompositionLocalProvider(LocalBottomBarInset provides barInset) {
        NavHost(
            navController = navController,
            startDestination = Route.DASHBOARD,
            modifier = Modifier.fillMaxSize(),
            // Tab ulazi sa strane na kojoj je u traci; korak u toku zdesna, povratak sleva.
            enterTransition = { screenEnter(tabDirection() ?: 1) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { screenEnter(tabDirection() ?: -1) },
            popExitTransition = { fadeOut(tween(200)) }
        ) {
            composable(Route.DASHBOARD) {
                DashboardScreen(
                    onOpenList = { navController.openTab(Route.LIST) },
                    onOpenSales = { navController.navigate(Route.SALES) },
                    receiptViewModel = receiptViewModel
                )
            }

            composable(Route.SALES) {
                SalesScreen(
                    onBack = navController::popBackStack,
                    onOpenProduct = { canonicalProductId ->
                        navController.navigate(Route.productDetails(canonicalProductId))
                    }
                )
            }

            composable(Route.CARDS) {
                // Strelica samo kad se došlo iz kupovine, da se na kasi vrati
                // na spisak; kao tab je nema.
                val fromPurchase = navController.previousBackStackEntry
                    ?.destination?.route == "purchase/{sessionId}"
                LoyaltyCardsScreen(onBack = if (fromPurchase) ({ navController.popBackStack() }) else null)
            }
            composable(Route.HISTORY) {
                PurchaseScreen(onBack = navController::popBackStack,
                    onOpen = { navController.navigate("purchase/$it") },
                    onOpenCards = { navController.navigate(Route.CARDS) },
                    receiptViewModel = receiptViewModel)
            }
            composable("purchase/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                PurchaseScreen(sessionId = entry.arguments?.getString("sessionId"),
                    onBack = navController::popBackStack,
                    onOpen = { navController.navigate("purchase/$it") },
                    onOpenCards = { navController.navigate(Route.CARDS) },
                    onStartNewList = {
                        // Iz kupovine po planu: nazad na spisak ispod nje. Iz istorije: spisak
                        // kao nov tab, bez vraćanja sačuvanog steka sa kupovinom na vrhu.
                        if (!navController.popBackStack(Route.LIST, inclusive = false)) {
                            navController.navigate(Route.LIST) {
                                popUpTo(navController.graph.findStartDestination().id)
                                launchSingleTop = true
                            }
                        }
                    },
                    receiptViewModel = receiptViewModel)
            }
            composable(Route.LIST) {
                ShoppingListScreen(
                    onOpenMatching = { listId ->
                        navController.navigate(Route.matching(listId))
                    },
                    onOpenProduct = { canonicalProductId ->
                        navController.navigate(
                            Route.productDetails(canonicalProductId)
                        )
                    }
                )
            }

            composable(
                route = Route.PRODUCT_DETAILS,
                arguments = listOf(
                    navArgument("canonicalProductId") {
                        type = NavType.LongType
                    }
                )
            ) {
                ProductDetailsScreen(onBack = navController::popBackStack)
            }

            composable(
                route = Route.MATCHING,
                arguments = listOf(
                    navArgument("listId") { type = NavType.LongType }
                )
            ) {
                MatchingScreen(
                    onBack = navController::popBackStack,
                    onContinue = { listId ->
                        navController.navigate(Route.location(listId))
                    }
                )
            }

            composable(
                route = Route.LOCATION,
                arguments = listOf(
                    navArgument("listId") { type = NavType.LongType }
                )
            ) { entry ->
                val listId = requireNotNull(entry.arguments?.getLong("listId"))
                LocationScreen(
                    onBack = navController::popBackStack,
                    onCalculate = { latitude, longitude, label ->
                        calculationSession.useLocation(latitude, longitude, label)
                        navController.navigate(Route.recommendation(listId))
                    }
                )
            }

            composable(
                route = Route.RECOMMENDATION,
                arguments = listOf(
                    navArgument("listId") { type = NavType.LongType }
                )
            ) { entry ->
                RecommendationScreen(
                    listId = requireNotNull(entry.arguments?.getLong("listId")),
                    location = calculationLocation,
                    originLabel = calculationOriginLabel,
                    onOpenPurchase = { navController.navigate("purchase/$it") },
                    onBack = navController::popBackStack
                )
            }
        }
        }
        }
        AnimatedVisibility(
            visible = onTab,
            enter = slideInVertically(tween(400, easing = ScreenEasing)) { it / 2 } + fadeIn(tween(300)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            FloatingNavBar(
                haze = haze,
                currentRoute = currentRoute,
                onTab = { navController.openTab(it.route) },
                onMenu = { showMenu = true },
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(NavBarMargin)
                    .onSizeChanged { barHeight = with(density) { it.height.toDp() } }
            )
        }
        // Meni je u istom prozoru kao sadržaj, da bi mogao da ga zamuti kao traka.
        AnimatedVisibility(visible = showMenu, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = stringResource(R.string.common_close),
                        onClick = { showMenu = false }
                    )
            )
        }
        // Izrasta iz dugmeta „Meni" (dole desno) i tamo se vraća; izlazi brže nego što ulazi.
        AnimatedVisibility(
            visible = showMenu,
            enter = scaleIn(tween(220, easing = ScreenEasing), initialScale = 0.94f, transformOrigin = MenuOrigin) +
                fadeIn(tween(160)),
            exit = scaleOut(tween(150), targetScale = 0.96f, transformOrigin = MenuOrigin) + fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val title = stringResource(R.string.app_menu)
            GlassCard(
                shape = NavBarShape,
                color = Color.Transparent,
                // Veća površina je deblje staklo: jače zamućenje i gušća boja, da se natpisi čitaju.
                backdrop = Modifier.navGlass(haze, NavBarShape, tintAlpha = 0.74f, blur = 32.dp),
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(NavBarMargin)
                    .fillMaxWidth()
                    .semantics { paneTitle = title }
            ) {
                menuContent()
            }
        }
    }
}

private val NavBarMargin = 12.dp
private val NavBarShape = RoundedCornerShape(30.dp)
private val MenuOrigin = TransformOrigin(0.9f, 1f)

/**
 * Staklo trake i menija: zamućen sadržaj ispod, preko njega boja trake malo
 * providna. Bez zamućenja (pre Androida 12) ostaje skoro neprovidno, inače se
 * spisak čita kroz natpise.
 */
@Composable
private fun Modifier.navGlass(
    haze: HazeState,
    shape: Shape,
    tintAlpha: Float = 0.6f,
    blur: Dp = 24.dp
): Modifier {
    val navBar = LocalAppColors.current.navBar
    return clip(shape).hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = MaterialTheme.colorScheme.background,
            tint = HazeTint(navBar.copy(alpha = tintAlpha)),
            blurRadius = blur,
            fallbackTint = HazeTint(navBar)
        )
    )
}
private val ScreenEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

private fun screenEnter(direction: Int) =
    slideInHorizontally(tween(400, easing = ScreenEasing)) { width -> direction * width / 12 } +
        fadeIn(tween(400, easing = ScreenEasing))

/** 1 kad je ciljni tab desno od polaznog, -1 kad je levo; null ako neki od njih nije tab. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabDirection(): Int? {
    val routes = Tab.entries.map { it.route }
    val from = routes.indexOf(initialState.destination.route)
    val to = routes.indexOf(targetState.destination.route)
    return if (from < 0 || to < 0) null else if (to >= from) 1 else -1
}

/** Donja traka lebdi kao staklena pilula; izabrani tab dobija pilulu iza ikonice. */
@Composable
private fun FloatingNavBar(
    haze: HazeState,
    currentRoute: String?,
    onTab: (Tab) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        shape = NavBarShape,
        color = Color.Transparent,
        backdrop = Modifier.navGlass(haze, NavBarShape),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)
                .selectableGroup()
        ) {
            Tab.entries.forEach { tab ->
                NavItem(
                    selected = tab.route == currentRoute,
                    icon = tab.icon,
                    label = stringResource(tab.label),
                    onClick = { onTab(tab) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tab-${tab.name.lowercase()}")
                )
            }
            NavItem(
                selected = false,
                icon = R.drawable.ic_menu,
                label = stringResource(R.string.app_menu),
                onClick = onMenu,
                modifier = Modifier
                    .weight(1f)
                    .testTag("open-menu")
            )
        }
    }
}

@Composable
private fun NavItem(
    selected: Boolean,
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    // Pilula se razvuče iza ikonice kad se tab izabere.
    val stretch by animateFloatAsState(
        targetValue = if (selected) 1f else 0.3f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "navPill"
    )
    val pillAlpha by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "navPillAlpha")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.size(width = 60.dp, height = 32.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = stretch
                        alpha = pillAlpha
                    }
                    .background(scheme.primaryContainer, RoundedCornerShape(16.dp))
            )
            AppIcon(
                icon,
                contentDescription = null,
                tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
        CompositionLocalProvider(
            LocalContentColor provides if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            LocalTextStyle provides MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold
            )
        ) {
            NavLabel(label)
        }
    }
}

/** Sa krupnim slovima pet natpisa ne staje, pa se smanjuju umesto da se seku. */
@Composable
private fun NavLabel(text: String) {
    // Sa pismom 2.0 se „Početna" smanjivala sama, pa su natpisi bili različiti.
    // Svi rastu najviše do 1,3×; tada staju, a smanjenje ostaje samo za krajnji slučaj.
    val fontScale = LocalDensity.current.fontScale
    val base = LocalTextStyle.current.fontSize
    val size = if (fontScale > 1.3f) base * (1.3f / fontScale) else base
    BasicText(
        text,
        maxLines = 1,
        style = LocalTextStyle.current.copy(color = LocalContentColor.current, fontSize = size),
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size)
    )
}

@Composable
private fun AppMenuContent(
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    onSales: () -> Unit,
    onScan: () -> Unit,
    onImportFile: () -> Unit,
    onHousehold: () -> Unit,
    onTripCosts: () -> Unit,
    onAbout: () -> Unit
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = AppSpacing.md)
    ) {
        listOf(
            Triple(R.string.app_menu_sales, R.drawable.ic_local_offer, onSales) to "menu-sales",
            Triple(R.string.app_menu_scan_receipt, R.drawable.ic_camera, onScan) to "menu-scan",
            Triple(R.string.app_menu_import_receipt, R.drawable.ic_image, onImportFile) to "menu-import-receipt",
            Triple(R.string.app_menu_household, R.drawable.ic_home, onHousehold) to "menu-household",
            Triple(R.string.app_menu_trip_costs, R.drawable.ic_directions, onTripCosts) to "menu-trip-costs",
            Triple(R.string.app_menu_about, R.drawable.ic_info, onAbout) to "menu-about"
        ).forEach { (entry, tag) ->
            val (label, icon, onClick) = entry
            MenuRow(
                icon = icon,
                label = stringResource(label),
                onClick = onClick,
                modifier = Modifier
                    .padding(horizontal = AppSpacing.sm)
                    .testTag(tag)
            )
        }
        Text(
            stringResource(R.string.app_theme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = AppSpacing.xl, end = AppSpacing.xl, top = AppSpacing.lg, bottom = AppSpacing.sm)
        )
        // Vodoravno skrolovanje: sa krupnim slovima četiri čipa ne staju u red.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.xl)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            listOf(
                ThemeChoice.SYSTEM to R.string.app_theme_system,
                ThemeChoice.LIGHT to R.string.app_theme_light,
                ThemeChoice.DARK to R.string.app_theme_dark,
                ThemeChoice.BLACK to R.string.app_theme_black
            ).forEach { (choice, label) ->
                ChoiceChip(
                    text = stringResource(label),
                    selected = choice == theme,
                    onClick = { onTheme(choice) },
                    modifier = Modifier.testTag("theme-${choice.name.lowercase()}")
                )
            }
        }
    }
}

/** Red menija: pločica sa ikonicom kao na početnoj, na dodir blago utone. */
@Composable
private fun MenuRow(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(source)
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
            .heightIn(min = 56.dp)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.lg)
    ) {
        IconTile(icon, size = 40.dp)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
