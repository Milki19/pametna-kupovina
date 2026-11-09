package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * How much a line asks for, one shape for the parser, the server and the
 * clients (V155): a number in a unit, in a number of packs. "limun 3" is
 * {3, PIECE, 1}, "2x mleko 1l" {1000, MILLILITER, 2}, "toalet papir 10
 * rolni" {10, PIECE, 1}, "mleko" {null, null, 1}.
 *
 * <p>It maps onto the columns shopping_list_item already has:
 * target_quantity, base_unit and quantity.
 *
 * @param number how much, in {@code unit}; null where the line says only a count of packs
 * @param unit   g, ml or piece; null together with {@code number}
 * @param packs  how many packs, at least one
 */
public record ListAmount(BigDecimal number, BaseUnit unit, BigDecimal packs) {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    public ListAmount {
        if ((number == null) != (unit == null)) {
            throw new IllegalArgumentException("Broj i jedinica idu zajedno");
        }
        if (number != null && number.signum() <= 0) {
            throw new IllegalArgumentException("Količina mora biti veća od nule");
        }
        packs = packs == null ? BigDecimal.ONE : packs;
        if (packs.signum() <= 0) {
            throw new IllegalArgumentException("Broj pakovanja mora biti veći od nule");
        }
    }

    public static ListAmount packsOnly(BigDecimal packs) {
        return new ListAmount(null, null, packs);
    }

    /** From the columns a shopping list item keeps. */
    public static ListAmount fromColumns(BigDecimal targetQuantity, String baseUnit, BigDecimal quantity) {
        BaseUnit unit = baseUnit == null || targetQuantity == null ? null : BaseUnit.fromDatabase(baseUnit);
        return new ListAmount(unit == null ? null : targetQuantity, unit, quantity);
    }

    public boolean counted() {
        return unit == BaseUnit.PIECE;
    }

    /** Everything the line asks, over all packs, in {@code unit}. */
    public Optional<BigDecimal> total() {
        return number == null ? Optional.empty() : Optional.of(number.multiply(packs));
    }

    /**
     * The pieces asked for, as grams to weigh out: "limun 3" with 100 g a
     * piece is 300 g, never 3 kg. Empty for a line that is not counted or a
     * piece without a known mass.
     */
    public Optional<BigDecimal> gramsForPieces(BigDecimal gramsPerPiece) {
        if (!counted() || gramsPerPiece == null || gramsPerPiece.signum() <= 0) {
            return Optional.empty();
        }
        return Optional.of(number.multiply(packs).multiply(gramsPerPiece));
    }

    /** The same in kilograms, to price a loose offer: 0,3 kg for three lemons. */
    public Optional<BigDecimal> kilogramsForPieces(BigDecimal gramsPerPiece) {
        return gramsForPieces(gramsPerPiece).map(grams -> grams.divide(THOUSAND, 4, RoundingMode.HALF_UP));
    }

    public BigDecimal packsOrOne() {
        return Objects.requireNonNullElse(packs, BigDecimal.ONE);
    }
}
