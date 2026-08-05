package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

public record ShoppingListMatchingResponse(
        Long listId,
        int totalItems,
        int automaticallyMatchedItems,
        int itemsNeedingConfirmation,
        int unmatchedItems,
        int flexibleItems,
        boolean readyForOptimization,
        List<Long> blockingItemIds,
        List<ShoppingItemMatchResult> items
) {
    public ShoppingListMatchingResponse {
        blockingItemIds = List.copyOf(blockingItemIds);
        items = List.copyOf(items);
    }
}
