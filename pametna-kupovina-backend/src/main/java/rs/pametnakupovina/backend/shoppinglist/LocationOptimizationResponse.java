package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

public record LocationOptimizationResponse(
        Long listId,
        String listName,
        double latitude,
        double longitude,
        BigDecimal costPerKm,
        String currency,
        String distanceMethod,
        String recommendation,
        PurchaseStrategyResponse recommendedStrategy,
        PurchaseStrategyResponse multiStoreStrategy,
        List<PurchaseStrategyResponse> singleStoreStrategies
) {
}