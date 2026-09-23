package rs.pametnakupovina.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.net.toUri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
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

    fun matching(listId: Long) = "matching/$listId"
    fun location(listId: Long) = "location/$listId"
    fun recommendation(listId: Long) = "recommendation/$listId"
    fun productDetails(canonicalProductId: Long) =
        "product/$canonicalProductId"
}

@Composable
fun PametnaKupovinaApp() {
    val navController = rememberNavController()
    val calculationSession: CalculationSessionViewModel = hiltViewModel()
    val calculationLocation by calculationSession.location
        .collectAsStateWithLifecycle()
    // Deljen sa dashboard-om i menijem, da skeniranje iz menija odmah osveži
    // brojke koje dashboard već prikazuje.
    val receiptViewModel: ReceiptViewModel = hiltViewModel()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                AppDrawerContent(
                    onScan = {
                        drawerScope.launch { drawerState.close() }
                        receiptViewModel.scan(context)
                    },
                    onCards = {
                        drawerScope.launch { drawerState.close() }
                        navController.navigate(Route.CARDS)
                    },
                    onHistory = {
                        drawerScope.launch { drawerState.close() }
                        navController.navigate("purchases")
                    },
                    onHousehold = {
                        drawerScope.launch { drawerState.close() }
                        showHousehold = true
                    },
                    onAbout = {
                        drawerScope.launch { drawerState.close() }
                        showAbout = true
                    }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = Route.DASHBOARD
        ) {
            composable(Route.DASHBOARD) {
                DashboardScreen(
                    onOpenMenu = { drawerScope.launch { drawerState.open() } },
                    onOpenList = { navController.navigate(Route.LIST) },
                    receiptViewModel = receiptViewModel
                )
            }

            composable(Route.CARDS) {
                LoyaltyCardsScreen(onBack = { navController.popBackStack() })
            }
            composable("purchases") {
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
                    onBack = { navController.popBackStack() },
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

@Composable
private fun AppDrawerContent(
    onScan: () -> Unit,
    onCards: () -> Unit,
    onHistory: () -> Unit,
    onHousehold: () -> Unit,
    onAbout: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = AppSpacing.md)) {
        Text(
            "Pametna kupovina",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md)
        )
        NavigationDrawerItem(
            label = { Text("Skener") },
            icon = { AppIcon(R.drawable.ic_camera, contentDescription = null) },
            selected = false,
            onClick = onScan,
            modifier = Modifier
                .padding(horizontal = AppSpacing.sm)
                .testTag("menu-scan")
        )
        NavigationDrawerItem(
            label = { Text("Kartice") },
            icon = { AppIcon(R.drawable.ic_card, contentDescription = null) },
            selected = false,
            onClick = onCards,
            modifier = Modifier
                .padding(horizontal = AppSpacing.sm)
                .testTag("menu-cards")
        )
        NavigationDrawerItem(
            label = { Text("Istorija") },
            icon = { AppIcon(R.drawable.ic_history, contentDescription = null) },
            selected = false,
            onClick = onHistory,
            modifier = Modifier
                .padding(horizontal = AppSpacing.sm)
                .testTag("menu-history")
        )
        NavigationDrawerItem(
            label = { Text("Domaćinstvo") },
            icon = { AppIcon(R.drawable.ic_home, contentDescription = null) },
            selected = false,
            onClick = onHousehold,
            modifier = Modifier
                .padding(horizontal = AppSpacing.sm)
                .testTag("menu-household")
        )
        NavigationDrawerItem(
            label = { Text("O aplikaciji") },
            icon = { AppIcon(R.drawable.ic_info, contentDescription = null) },
            selected = false,
            onClick = onAbout,
            modifier = Modifier
                .padding(horizontal = AppSpacing.sm)
                .testTag("menu-about")
        )
    }
}
