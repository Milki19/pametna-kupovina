package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

/**
 * A pasted line. `quantity` counts packages ("2x mleko"), while
 * `targetQuantity` is the total amount the line asked for ("ćevapi 3kg"),
 * already converted to the catalogue's base unit.
 */
public record ParsedShoppingListLine(
        String name,
        String rawInput,
        BigDecimal quantity,
        BigDecimal targetQuantity,
        String baseUnit
) {
    public ParsedShoppingListLine(
            String name,
            String rawInput,
            BigDecimal quantity
    ) {
        this(name, rawInput, quantity, null, null);
    }
}
