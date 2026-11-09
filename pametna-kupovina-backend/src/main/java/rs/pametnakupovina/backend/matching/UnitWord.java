package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;

/**
 * One word of the unit dictionary (app.unit_word, V155): "kg" is a MEASURE
 * of 1000 g, "kom" a COUNT, "kesica" a PACK, "x" a MULTIPLIER.
 *
 * @param baseUnit   null for PACK and MULTIPLIER
 * @param factor     how many {@code baseUnit} one of this is
 * @param insidePack a COUNT that is counted inside one pack (V157): "toalet
 *                   papir 10 rolni" is one pack of ten rolls, not ten packs
 */
public record UnitWord(String word, Role role, BaseUnit baseUnit, BigDecimal factor, boolean insidePack) {

    public UnitWord(String word, Role role, BaseUnit baseUnit, BigDecimal factor) {
        this(word, role, baseUnit, factor, false);
    }

    public enum Role {
        /** Mass or volume: kg, g, l, dl, ml. */
        MEASURE,
        /** A piece: kom, komad, pcs; a roll inside a pack (insidePack). */
        COUNT,
        /** A pack that is counted: kesica, kutija, flaša, limenka, pakovanje. */
        PACK,
        /** How many packs: 3x, x3, puta. */
        MULTIPLIER
    }

    /** {@code amount} of this unit in its base unit: 1,5 kg is 1500 g. */
    public BigDecimal inBaseUnit(BigDecimal amount) {
        return amount.multiply(factor);
    }
}
