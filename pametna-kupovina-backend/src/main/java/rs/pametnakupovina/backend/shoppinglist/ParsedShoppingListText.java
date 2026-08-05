package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

public record ParsedShoppingListText(
        List<ParsedShoppingListLine> items,
        int ignoredBlankLineCount
) {
}
