package rs.pametnakupovina.backend.shoppinglist;

import java.math.BigDecimal;

/**
 * A pasted line. `quantity` counts packages ("2x mleko"), while
 * `targetQuantity` is the total amount the line asked for ("ćevapi 3kg"),
 * already converted to the catalogue's base unit. `nameWithAmount` keeps the
 * amount as written, for a line that turns out to name one product, and
 * `bareNumber` is a trailing number without a unit ("kisela voda 1.75") that
 * only the kind of product can explain.
 */
public record ParsedShoppingListLine(
        String name,
        String rawInput,
        BigDecimal quantity,
        BigDecimal targetQuantity,
        String baseUnit,
        String nameWithAmount,
        BigDecimal bareNumber
) {
    public ParsedShoppingListLine(
            String name,
            String rawInput,
            BigDecimal quantity,
            BigDecimal targetQuantity,
            String baseUnit
    ) {
        this(name, rawInput, quantity, targetQuantity, baseUnit, name, null);
    }

    public ParsedShoppingListLine(
            String name,
            String rawInput,
            BigDecimal quantity
    ) {
        this(name, rawInput, quantity, null, null);
    }
}
