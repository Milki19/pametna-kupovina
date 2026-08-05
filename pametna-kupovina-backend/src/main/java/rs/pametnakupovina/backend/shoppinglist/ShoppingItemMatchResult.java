package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.matching.FuzzyProductCandidate;

import java.math.BigDecimal;
import java.util.List;

public record ShoppingItemMatchResult(
        Long itemId,
        String requestedName,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        Long matchedCanonicalProductId,
        Long decisionId,
        BigDecimal score,
        boolean blocksOptimization,
        String explanation,
        List<FuzzyProductCandidate> candidates
) {
    public ShoppingItemMatchResult {
        candidates = List.copyOf(candidates);
    }
}
