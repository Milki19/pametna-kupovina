package rs.pametnakupovina.backend.shoppinglist;

import java.time.LocalDate;
import java.util.List;

public record ShoppingRecommendationResponse(
        Long listId,
        String listName,
        LocalDate requestedDate,
        int candidateStoreCount,
        int evaluatedSingleStoreScenarios,
        int evaluatedTwoStoreCombinations,
        OptimizationAssumptions assumptions,
        OptimizationScenarioResponse singleStore,
        OptimizationScenarioResponse recommendedBalance,
        OptimizationScenarioResponse lowestPrice,
        List<UnlocatedPriceOption> unlocatedPriceOptions,
        String disclaimer
) {
}
