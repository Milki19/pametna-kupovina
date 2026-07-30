package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;
import java.util.List;

public record ShoppingListOptimizationResponse(
        Long listId,
        String listName,
        int totalItems,
        int matchedItems,
        int unmatchedItems,
        int storesUsedForLowestPrice,
        BigDecimal lowestPriceTotal,
        RetailerBasketOption bestSingleStore,
        BigDecimal savingsUsingMultipleStores,
        String recommendation,
        List<ShoppingListPriceItem> lowestPriceItems,
        List<RetailerBasketOption> singleStoreOptions
) {
}