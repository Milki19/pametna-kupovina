package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record OptimizationScenarioResponse(
        RecommendationScenarioType type,
        boolean available,
        boolean complete,
        String explanation,
        int coveredItems,
        int unmatchedItems,
        int unavailableItems,
        int stopCount,
        BigDecimal basketCost,
        double routeDistanceKm,
        long routeDurationSeconds,
        BigDecimal travelCost,
        BigDecimal timeCost,
        BigDecimal stopCost,
        BigDecimal totalCost,
        BigDecimal savingsComparedWithSingleStore,
        String routeProvider,
        String distanceMethod,
        boolean approximateRoute,
        List<String> priceSources,
        LocalDate dataAsOf,
        List<RecommendationStoreResponse> stores,
        List<RecommendationItemResponse> items,
        String disclaimer,
        // Small items left for the way rather than a second shop; null when
        // the plan leaves nothing out on purpose.
        OnTheWay onTheWay
) {
    public OptimizationScenarioResponse {
        priceSources = List.copyOf(priceSources);
        stores = List.copyOf(stores);
        items = List.copyOf(items);
    }
}
