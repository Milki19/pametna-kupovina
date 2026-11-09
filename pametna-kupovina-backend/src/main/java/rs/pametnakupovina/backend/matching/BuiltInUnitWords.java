package rs.pametnakupovina.backend.matching;

import rs.pametnakupovina.backend.matching.UnitWord.Role;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Serbian unit words of V157, for a parser made without a database (unit
 * tests, the test set tools) and for a database whose dictionary is still
 * empty. The database is the source; UnitWordsMatchV157Test keeps the two
 * the same.
 */
public final class BuiltInUnitWords {

    /** sr-Latn, longest first, as {@link UnitVocabulary#words} gives them. */
    public static final List<UnitWord> SERBIAN = Stream.of(
            word("kg", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kilogram", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kilograma", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kila", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kile", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kilo", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("kilu", Role.MEASURE, BaseUnit.GRAM, 1000, false),
            word("g", Role.MEASURE, BaseUnit.GRAM, 1, false),
            word("gr", Role.MEASURE, BaseUnit.GRAM, 1, false),
            word("gram", Role.MEASURE, BaseUnit.GRAM, 1, false),
            word("grama", Role.MEASURE, BaseUnit.GRAM, 1, false),
            word("l", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("lit", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("litar", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("litra", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("litre", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("litara", Role.MEASURE, BaseUnit.MILLILITER, 1000, false),
            word("dl", Role.MEASURE, BaseUnit.MILLILITER, 100, false),
            word("ml", Role.MEASURE, BaseUnit.MILLILITER, 1, false),
            word("mililitar", Role.MEASURE, BaseUnit.MILLILITER, 1, false),
            word("mililitara", Role.MEASURE, BaseUnit.MILLILITER, 1, false),
            word("kom", Role.COUNT, BaseUnit.PIECE, 1, false),
            word("komad", Role.COUNT, BaseUnit.PIECE, 1, false),
            word("komada", Role.COUNT, BaseUnit.PIECE, 1, false),
            word("komadi", Role.COUNT, BaseUnit.PIECE, 1, false),
            word("pcs", Role.COUNT, BaseUnit.PIECE, 1, false),
            word("rolna", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("rolne", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("rolni", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("rolnu", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("rola", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("role", Role.COUNT, BaseUnit.PIECE, 1, true),
            word("kesica", Role.PACK, null, 1, false),
            word("kesice", Role.PACK, null, 1, false),
            word("kesicu", Role.PACK, null, 1, false),
            word("kesici", Role.PACK, null, 1, false),
            word("kutija", Role.PACK, null, 1, false),
            word("kutije", Role.PACK, null, 1, false),
            word("kutiju", Role.PACK, null, 1, false),
            word("kutijica", Role.PACK, null, 1, false),
            word("kutijice", Role.PACK, null, 1, false),
            word("flasa", Role.PACK, null, 1, false),
            word("flase", Role.PACK, null, 1, false),
            word("flasu", Role.PACK, null, 1, false),
            word("flasica", Role.PACK, null, 1, false),
            word("flasice", Role.PACK, null, 1, false),
            word("limenka", Role.PACK, null, 1, false),
            word("limenke", Role.PACK, null, 1, false),
            word("limenku", Role.PACK, null, 1, false),
            word("limenki", Role.PACK, null, 1, false),
            word("pakovanje", Role.PACK, null, 1, false),
            word("pakovanja", Role.PACK, null, 1, false),
            word("pakovanju", Role.PACK, null, 1, false),
            word("pak", Role.PACK, null, 1, false),
            word("x", Role.MULTIPLIER, null, 1, false),
            word("puta", Role.MULTIPLIER, null, 1, false)
    ).sorted(Comparator.comparingInt((UnitWord word) -> word.word().length()).reversed()).toList();

    private BuiltInUnitWords() {
    }

    private static UnitWord word(String word, Role role, BaseUnit baseUnit, int factor, boolean insidePack) {
        return new UnitWord(word, role, baseUnit, BigDecimal.valueOf(factor), insidePack);
    }
}
