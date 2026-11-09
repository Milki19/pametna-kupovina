package rs.pametnakupovina.backend.shoppinglist;

import java.time.LocalDate;
import java.util.List;

public record ShoppingRecommendationResponse(
        @InternalListId Long listId,
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
    /**
     * Every plan's totals and coverage, and the items of {@code plan} only;
     * the others come when asked for. Without a plan, the whole answer, as
     * the apps before 2.1 read it.
     */
    public ShoppingRecommendationResponse detailsOf(RecommendationScenarioType plan) {
        if (plan == null) {
            return this;
        }
        return new ShoppingRecommendationResponse(listId, listName, requestedDate,
                candidateStoreCount, evaluatedSingleStoreScenarios,
                evaluatedTwoStoreCombinations, assumptions,
                plan == RecommendationScenarioType.SINGLE_STORE ? singleStore : singleStore.withoutItems(),
                plan == RecommendationScenarioType.RECOMMENDED_BALANCE
                        ? recommendedBalance : recommendedBalance.withoutItems(),
                plan == RecommendationScenarioType.LOWEST_PRICE ? lowestPrice : lowestPrice.withoutItems(),
                unlocatedPriceOptions, disclaimer);
    }
}
