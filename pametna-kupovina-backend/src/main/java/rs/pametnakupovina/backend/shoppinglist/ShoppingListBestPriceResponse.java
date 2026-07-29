package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

public record ShoppingListBestPriceResponse(
        Long listId,
        String listName,
        int matchedItems,
        int unmatchedItems,
        BigDecimal totalPrice,
        List<ShoppingListPriceItem> items
) {
}