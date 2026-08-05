package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

public record ParsedShoppingListLine(
        String name,
        String rawInput,
        BigDecimal quantity
) {
}
