package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;

/**
 * How a chain sells a product (V155): by the piece, loose by the kilogram, or
 * in a pack whose net mass or volume is known. A lemon sold by the piece and
 * one sold by the kilogram are the same fruit at prices that cannot be
 * compared until a piece has a mass ({@link PieceMassCatalog}).
 */
public enum SaleMode {
    PIECE,
    LOOSE_KG,
    PACKAGE;

    /**
     * The same rule as {@code app.sale_mode_of} in V155: a hand-set mode
     * first, then what the price lists say (sale_unit, package size).
     *
     * @return null where nothing says how the product is sold
     */
    public static SaleMode of(String override, String saleUnit, BigDecimal quantityValue, String baseUnit) {
        if (override != null) {
            return SaleMode.valueOf(override);
        }
        if ("kg".equals(saleUnit)) {
            return LOOSE_KG;
        }
        if (quantityValue != null && ("g".equals(baseUnit) || "ml".equals(baseUnit))) {
            return PACKAGE;
        }
        if ("kom".equals(saleUnit) || "piece".equals(baseUnit)) {
            return PIECE;
        }
        return null;
    }
}
