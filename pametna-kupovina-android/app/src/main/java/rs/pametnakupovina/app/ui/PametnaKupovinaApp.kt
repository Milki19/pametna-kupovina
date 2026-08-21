package rs.pametnakupovina.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rs.pametnakupovina.app.ui.screens.LocationScreen
import rs.pametnakupovina.app.ui.screens.MatchingScreen
import rs.pametnakupovina.app.ui.screens.ProductDetailsScreen
import rs.pametnakupovina.app.ui.screens.RecommendationScreen
import rs.pametnakupovina.app.ui.screens.ShoppingListScreen

private object Route {
    const val LIST = "list"
    const val MATCHING = "matching/{listId}"
    const val LOCATION = "location/{listId}"
    const val RECOMMENDATION = "recommendation/{listId}"
    const val PRODUCT_DETAILS = "product/{canonicalProductId}"

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

    NavHost(
        navController = navController,
        startDestination = Route.LIST
    ) {
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
                onBack = navController::popBackStack
            )
        }
    }
}
