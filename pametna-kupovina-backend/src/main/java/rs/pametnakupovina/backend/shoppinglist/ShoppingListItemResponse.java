package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ShoppingListItemResponse(
        Long id,
        String name,
        String rawInput,
        String barcode,
        BigDecimal quantity,
        ShoppingItemRule matchingRule,
        ShoppingItemMatchingStatus matchingStatus,
        Long matchedCanonicalProductId,
        Long matchingDecisionId,
        BigDecimal matchingScore,
        String matchingAlgorithmVersion,
        FlexibleItemConstraints flexibleConstraints,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
