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
        OnTheWay onTheWay,
        // How many lines the list has and which of them this plan prices:
        // enough to show the plan's tile and compare it, without its items.
        int itemCount,
        List<Long> availableItemIds,
        // False when the answer leaves the items out; they come on request
        // (preporuke u dva koraka, šesti test: 100 stavki su bile 212 KB).
        boolean detailed
) {
    public OptimizationScenarioResponse {
        priceSources = List.copyOf(priceSources);
        stores = List.copyOf(stores);
        items = List.copyOf(items);
        availableItemIds = List.copyOf(availableItemIds);
    }

    /** A plan with its items, as worked out. */
    public OptimizationScenarioResponse(
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
            OnTheWay onTheWay
    ) {
        this(type, available, complete, explanation, coveredItems, unmatchedItems,
                unavailableItems, stopCount, basketCost, routeDistanceKm,
                routeDurationSeconds, travelCost, timeCost, stopCost, totalCost,
                savingsComparedWithSingleStore, routeProvider, distanceMethod,
                approximateRoute, priceSources, dataAsOf, stores, items, disclaimer,
                onTheWay, items.size(), availableIds(items), true);
    }

    private static List<Long> availableIds(List<RecommendationItemResponse> items) {
        return items.stream()
                .filter(item -> item.resultStatus() == RecommendationItemStatus.AVAILABLE)
                .map(RecommendationItemResponse::itemId)
                .toList();
    }

    /**
     * The same plan without its items: totals, coverage, shops and route stay,
     * so it can be shown and compared until someone opens it.
     */
    public OptimizationScenarioResponse withoutItems() {
        if (!detailed) {
            return this;
        }
        return new OptimizationScenarioResponse(type, available, complete, explanation,
                coveredItems, unmatchedItems, unavailableItems, stopCount, basketCost,
                routeDistanceKm, routeDurationSeconds, travelCost, timeCost, stopCost,
                totalCost, savingsComparedWithSingleStore, routeProvider, distanceMethod,
                approximateRoute, priceSources, dataAsOf, stores, List.of(), disclaimer,
                onTheWay, itemCount, availableItemIds, false);
    }
}
