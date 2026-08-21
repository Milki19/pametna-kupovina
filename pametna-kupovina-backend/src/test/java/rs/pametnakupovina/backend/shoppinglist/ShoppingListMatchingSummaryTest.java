package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingListMatchingSummaryTest {

    @Test
    void countsConfirmedItemsSeparatelyFromAutomaticMatches() {
        List<ShoppingItemMatchResult> items = List.of(
                item(1L, ShoppingItemMatchingStatus.AUTO_MATCHED),
                item(2L, ShoppingItemMatchingStatus.CONFIRMED),
                item(3L, ShoppingItemMatchingStatus.CONFIRMED)
        );

        assertThat(ShoppingListMatchingService.countItemsWithStatus(
                items,
                ShoppingItemMatchingStatus.AUTO_MATCHED
        )).isEqualTo(1);
        assertThat(ShoppingListMatchingService.countItemsWithStatus(
                items,
                ShoppingItemMatchingStatus.CONFIRMED
        )).isEqualTo(2);
    }

    private ShoppingItemMatchResult item(
            Long id,
            ShoppingItemMatchingStatus status
    ) {
        return new ShoppingItemMatchResult(
                id,
                "Stavka " + id,
                ShoppingItemRule.EXACT_PRODUCT,
                status,
                id,
                null,
                null,
                false,
                "",
                List.of()
        );
    }
}
