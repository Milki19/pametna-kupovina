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
        Long matchedProductFamilyId,
        Long matchingDecisionId,
        BigDecimal matchingScore,
        String matchingAlgorithmVersion,
        FlexibleItemConstraints flexibleConstraints,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public ShoppingListItemResponse(
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
        this(
                id,
                name,
                rawInput,
                barcode,
                quantity,
                matchingRule,
                matchingStatus,
                matchedCanonicalProductId,
                null,
                matchingDecisionId,
                matchingScore,
                matchingAlgorithmVersion,
                flexibleConstraints,
                createdAt,
                updatedAt
        );
    }
}
