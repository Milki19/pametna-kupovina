package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

public record PurchaseStrategyResponse(
        String strategy,
        boolean available,
        List<String> retailerCodes,
        BigDecimal basketTotal,
        double routeDistanceKm,
        BigDecimal travelCost,
        BigDecimal finalTotal,
        String reason,
        List<RouteStopResponse> route
) {
}