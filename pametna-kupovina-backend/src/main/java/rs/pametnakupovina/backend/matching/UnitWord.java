package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;

/**
 * One word of the unit dictionary (app.unit_word, V155): "kg" is a MEASURE
 * of 1000 g, "kom" a COUNT, "kesica" a PACK, "x" a MULTIPLIER.
 *
 * @param baseUnit null for PACK and MULTIPLIER
 * @param factor   how many {@code baseUnit} one of this is
 */
public record UnitWord(String word, Role role, BaseUnit baseUnit, BigDecimal factor) {

    public enum Role {
        /** Mass or volume: kg, g, l, dl, ml. */
        MEASURE,
        /** A piece: kom, komad, kos. */
        COUNT,
        /** A pack that is counted: kesica, rolna, kutija, flaša, limenka. */
        PACK,
        /** How many packs: 3x, x3, puta. */
        MULTIPLIER
    }

    /** {@code amount} of this unit in its base unit: 1,5 kg is 1500 g. */
    public BigDecimal inBaseUnit(BigDecimal amount) {
        return amount.multiply(factor);
    }
}
