package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

public record PasteShoppingListItemsResponse(
        int createdCount,
        int ignoredBlankLineCount,
        List<ShoppingListItemResponse> items
) {
}
