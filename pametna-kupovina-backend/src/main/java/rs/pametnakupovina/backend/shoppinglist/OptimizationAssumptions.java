package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record OptimizationAssumptions(
        int candidateRadiusMeters,
        int maxCandidateStores,
        BigDecimal costPerKm,
        BigDecimal valuePerHour,
        BigDecimal costPerStop,
        BigDecimal straightLineAverageSpeedKmh,
        String currency
) {
}
