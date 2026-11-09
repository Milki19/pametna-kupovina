package rs.pametnakupovina.backend.matching;

import rs.pametnakupovina.backend.market.Market;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Base kind → kind → required attributes (V155): fish → hake → frozen
 * fillet. A line's usual price is judged within its base kind, and a
 * variant the line names is a filter, not a hint.
 */
public interface KindTaxonomy {

    /** The base kind of a kind; the kind itself where it has none. */
    long baseKindOf(long productTypeId);

    /** The attribute codes a product of the kind must have set to be chosen for a line that names them. */
    Set<String> requiredAttributes(long productTypeId);

    /**
     * The variants a line names, each by a whole word ({@link WholeWords}):
     * "coca cola zero" gives SUGAR = ZERO. In the order of the dictionary's
     * priority; one per attribute.
     *
     * @param normalizedLine as ProductNameNormalizer writes it
     */
    List<VariantWord> variantsIn(Market market, String normalizedLine);

    /** The first variant for one attribute, if the line names it. */
    default Optional<VariantWord> variantIn(Market market, String normalizedLine, String attributeCode) {
        return variantsIn(market, normalizedLine).stream()
                .filter(variant -> variant.attributeCode().equals(attributeCode))
                .findFirst();
    }
}
