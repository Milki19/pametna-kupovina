package rs.pametnakupovina.backend.shoppinglist;

import java.util.List;

/**
 * `lineNumbers` holds, for each item, the line of the pasted text it came
 * from (1 for the first line), so a refused item can point at its line.
 */
public record ParsedShoppingListText(
        List<ParsedShoppingListLine> items,
        int ignoredBlankLineCount,
        List<Integer> lineNumbers
) {
    public ParsedShoppingListText(
            List<ParsedShoppingListLine> items,
            int ignoredBlankLineCount
    ) {
        this(items, ignoredBlankLineCount, List.of());
    }

    public int lineNumber(int itemIndex) {
        return itemIndex < lineNumbers.size()
                ? lineNumbers.get(itemIndex)
                : itemIndex + 1;
    }
}
