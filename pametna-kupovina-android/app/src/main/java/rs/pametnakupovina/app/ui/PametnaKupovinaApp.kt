package rs.pametnakupovina.app.ui

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import rs.pametnakupovina.app.ui.components.AppIcon
import rs.pametnakupovina.app.ui.components.AppSpacing
import rs.pametnakupovina.app.ui.screens.AboutDialog
import rs.pametnakupovina.app.ui.screens.HouseholdDialog
import rs.pametnakupovina.app.ui.screens.DashboardScreen
import rs.pametnakupovina.app.ui.screens.LocationScreen
import rs.pametnakupovina.app.ui.screens.LoyaltyCardsScreen
import rs.pametnakupovina.app.ui.screens.MatchingScreen
import rs.pametnakupovina.app.ui.screens.ProductDetailsScreen
import rs.pametnakupovina.app.ui.screens.RecommendationScreen
import rs.pametnakupovina.app.ui.screens.ShoppingListScreen
import rs.pametnakupovina.app.ui.screens.PurchaseScreen

private object Route {
    const val DASHBOARD = "dashboard"
    const val LIST = "list"
    const val MATCHING = "matching/{listId}"
    const val LOCATION = "location/{listId}"
    const val RECOMMENDATION = "recommendation/{listId}"
    const val PRODUCT_DETAILS = "product/{canonicalProductId}"
    const val CARDS = "cards"
    const val HISTORY = "purchases"

    fun matching(listId: Long) = "matching/$listId"
    fun location(listId: Long) = "location/$listId"
    fun recommendation(listId: Long) = "recommendation/$listId"
    fun productDetails(canonicalProductId: Long) =
        "product/$canonicalProductId"
}

/** Tabovi donje trake; ostali ekrani su koraci u toku i traku skrivaju. */
private enum class Tab(val route: String, val label: String, @DrawableRes val icon: Int) {
    HOME(Route.DASHBOARD, "Početna", R.drawable.ic_home),
    LIST(Route.LIST, "Spisak", R.drawable.ic_content_paste),
    CARDS(Route.CARDS, "Kartice", R.drawable.ic_card),
    HISTORY(Route.HISTORY, "Istorija", R.drawable.ic_history)
}

/** Kao tab: jedan primerak svakog taba, a svaki pamti dokle se stiglo u njemu. */
private fun NavHostController.openTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PametnaKupovinaApp() {
    val navController = rememberNavController()
    val calculationSession: CalculationSessionViewModel = hiltViewModel()
    val calculationLocation by calculationSession.location
        .collectAsStateWithLifecycle()
    // Deljen sa dashboard-om i menijem, da skeniranje iz menija odmah osveži
    // brojke koje dashboard već prikazuje.
    val receiptViewModel: ReceiptViewModel = hiltViewModel()

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    var showMenu by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showHousehold by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

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

    if (showMenu) {
        ModalBottomSheet(
            onDismissRequest = { showMenu = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            AppMenuContent(
                onScan = {
                    showMenu = false
                    // Ishod skeniranja se javlja na početnom ekranu.
                    navController.openTab(Route.DASHBOARD)
                    receiptViewModel.scan(context)
                },
                onHousehold = {
                    showMenu = false
                    showHousehold = true
                },
                onAbout = {
                    showMenu = false
                    showAbout = true
                }
            )
        }
    }

    Scaffold(
        // Gornji razmak daje svaki ekran sam, kroz svoju traku sa naslovom.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (Tab.entries.any { it.route == currentRoute }) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab.route == currentRoute,
                            onClick = { navController.openTab(tab.route) },
                            icon = { AppIcon(tab.icon, contentDescription = null) },
                            label = { NavLabel(tab.label) },
                            modifier = Modifier.testTag("tab-${tab.name.lowercase()}")
                        )
                    }
                    NavigationBarItem(
                        selected = false,
                        onClick = { showMenu = true },
                        icon = { AppIcon(R.drawable.ic_menu, contentDescription = null) },
                        label = { NavLabel("Meni") },
                        modifier = Modifier.testTag("open-menu")
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Route.DASHBOARD,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable(Route.DASHBOARD) {
                DashboardScreen(
                    onOpenList = { navController.openTab(Route.LIST) },
                    receiptViewModel = receiptViewModel
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
                    onOpenCards = { navController.navigate(Route.CARDS) })
            }
            composable("purchase/{sessionId}", arguments = listOf(navArgument("sessionId") { type = NavType.StringType })) { entry ->
                PurchaseScreen(sessionId = entry.arguments?.getString("sessionId"),
                    onBack = navController::popBackStack,
                    onOpen = { navController.navigate("purchase/$it") },
                    onOpenCards = { navController.navigate(Route.CARDS) })
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
                    onCalculate = { latitude, longitude ->
                        calculationSession.useLocation(latitude, longitude)
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
                    onOpenPurchase = { navController.navigate("purchase/$it") },
                    onBack = navController::popBackStack
                )
            }
        }
    }
}

/** Sa krupnim slovima pet natpisa ne staje, pa se smanjuju umesto da se seku. */
@Composable
private fun NavLabel(text: String) {
    BasicText(
        text,
        maxLines = 1,
        style = LocalTextStyle.current.copy(color = LocalContentColor.current),
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = LocalTextStyle.current.fontSize)
    )
}

@Composable
private fun AppMenuContent(
    onScan: () -> Unit,
    onHousehold: () -> Unit,
    onAbout: () -> Unit
) {
    Column(modifier = Modifier.padding(bottom = AppSpacing.lg)) {
        listOf(
            Triple("Skeniraj račun", R.drawable.ic_camera, onScan) to "menu-scan",
            Triple("Domaćinstvo", R.drawable.ic_home, onHousehold) to "menu-household",
            Triple("O aplikaciji", R.drawable.ic_info, onAbout) to "menu-about"
        ).forEach { (entry, tag) ->
            val (label, icon, onClick) = entry
            NavigationDrawerItem(
                label = { Text(label) },
                icon = { AppIcon(icon, contentDescription = null) },
                selected = false,
                onClick = onClick,
                modifier = Modifier
                    .padding(horizontal = AppSpacing.sm)
                    .testTag(tag)
            )
        }
    }
}
