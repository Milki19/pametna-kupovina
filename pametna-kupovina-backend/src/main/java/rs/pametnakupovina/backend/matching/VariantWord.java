package rs.pametnakupovina.backend.matching;

/**
 * A list word that asks for a variant (app.variant_word, V155): "zero" is
 * SUGAR = ZERO, "kapsule" FORM = CAPSULES. A product without that value of
 * the attribute is no choice for the line.
 *
 * @param attributeCode product_attribute_definition.code
 * @param value         retailer_product_attribute.text_value it requires
 */
public record VariantWord(String word, String attributeCode, String value) {
}
