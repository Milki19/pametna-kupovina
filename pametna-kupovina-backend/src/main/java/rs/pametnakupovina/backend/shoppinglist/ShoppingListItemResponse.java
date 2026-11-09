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
        OffsetDateTime updatedAt,
        String note,
        BigDecimal pieceGrams
) {

    /**
     * `pieceGrams` is what one counted piece weighs where the list counts
     * fruit or vegetables ("limun 3": 100 g), so the list can say
     * "3 kom (≈0,3 kg)" (V156). Null for everything else.
     */
    public ShoppingListItemResponse(
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
            OffsetDateTime updatedAt,
            String note
    ) {
        this(id, name, rawInput, barcode, quantity, matchingRule, matchingStatus,
                matchedCanonicalProductId, matchedProductFamilyId, matchingDecisionId,
                matchingScore, matchingAlgorithmVersion, flexibleConstraints,
                createdAt, updatedAt, note, null);
    }

    /** As read from the list, with the mass of one counted piece. */
    public ShoppingListItemResponse(
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
            OffsetDateTime updatedAt,
            BigDecimal pieceGrams
    ) {
        this(id, name, rawInput, barcode, quantity, matchingRule, matchingStatus,
                matchedCanonicalProductId, matchedProductFamilyId, matchingDecisionId,
                matchingScore, matchingAlgorithmVersion, flexibleConstraints,
                createdAt, updatedAt, ShoppingListTextParser.noteOf(rawInput), pieceGrams);
    }

    /** `note` is read from the line as written: "čokolada za malog" → "za malog". */
    public ShoppingListItemResponse(
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
        this(
                id,
                name,
                rawInput,
                barcode,
                quantity,
                matchingRule,
                matchingStatus,
                matchedCanonicalProductId,
                matchedProductFamilyId,
                matchingDecisionId,
                matchingScore,
                matchingAlgorithmVersion,
                flexibleConstraints,
                createdAt,
                updatedAt,
                ShoppingListTextParser.noteOf(rawInput)
        );
    }

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
