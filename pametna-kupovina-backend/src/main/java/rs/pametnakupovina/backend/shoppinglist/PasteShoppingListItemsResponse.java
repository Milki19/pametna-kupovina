package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

/**
 * The good lines of a pasted list are added; `rejectedLines` are the ones
 * left out, each with its line number and why, so the app can mark them.
 */
public record PasteShoppingListItemsResponse(
        int createdCount,
        int ignoredBlankLineCount,
        List<ShoppingListItemResponse> items,
        List<RejectedLine> rejectedLines
) {
    public PasteShoppingListItemsResponse(
            int createdCount,
            int ignoredBlankLineCount,
            List<ShoppingListItemResponse> items
    ) {
        this(createdCount, ignoredBlankLineCount, items, List.of());
    }

    /** `field` is "quantity" or "name" when the reason is about one of them. */
    public record RejectedLine(
            int lineNumber,
            String text,
            String field,
            String message
    ) {
    }
}
