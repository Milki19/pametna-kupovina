package rs.pametnakupovina.backend.shoppinglist;

import rs.pametnakupovina.backend.matching.BaseUnit;
import rs.pametnakupovina.backend.matching.ListAmount;

import java.math.BigDecimal;

/**
 * A pasted line. `quantity` counts packages ("2x mleko"), while
 * `targetQuantity` is the total amount the line asked for ("ćevapi 3kg"),
 * already converted to the catalogue's base unit. `nameWithAmount` keeps the
 * amount as written, for a line that turns out to name one product, and
 * `bareNumber` is a trailing number without a unit ("kisela voda 1.75") that
 * only the kind of product can explain. `piecesInPack` is how many pieces one
 * pack holds, where the line counts what is inside it ("toalet papir 10
 * rolni" is one pack of ten rolls, V157); the name keeps those words, so the
 * pack is looked for by them. `packsNamed` says the count was written with a
 * word for a pack ("4 kutije jaja"), so it is never read as pieces.
 */
public record ParsedShoppingListLine(
        String name,
        String rawInput,
        BigDecimal quantity,
        BigDecimal targetQuantity,
        String baseUnit,
        String nameWithAmount,
        BigDecimal bareNumber,
        BigDecimal piecesInPack,
        boolean packsNamed
) {
    public ParsedShoppingListLine(
            String name,
            String rawInput,
            BigDecimal quantity,
            BigDecimal targetQuantity,
            String baseUnit,
            String nameWithAmount,
            BigDecimal bareNumber,
            BigDecimal piecesInPack
    ) {
        this(name, rawInput, quantity, targetQuantity, baseUnit, nameWithAmount, bareNumber, piecesInPack, false);
    }

    public ParsedShoppingListLine(
            String name,
            String rawInput,
            BigDecimal quantity,
            BigDecimal targetQuantity,
            String baseUnit,
            String nameWithAmount,
            BigDecimal bareNumber
    ) {
        this(name, rawInput, quantity, targetQuantity, baseUnit, nameWithAmount, bareNumber, null);
    }

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

    /**
     * The line as the shared amount model reads it (V155): "2x ćevapi 3kg"
     * is {3000, GRAM, 2}, "toalet papir 10 rolni" {10, PIECE, 1}, "grcki
     * jogurt 3x" {null, null, 3}.
     */
    public ListAmount amount() {
        if (piecesInPack != null) {
            return new ListAmount(piecesInPack, BaseUnit.PIECE, quantity);
        }
        return ListAmount.fromColumns(targetQuantity, baseUnit, quantity);
    }
}
