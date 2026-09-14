package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The owner's slava list, line by line: what is a choice among offers and
 * what names one product.
 */
class ShoppingLineInterpreterTest {

    private final ShoppingIntentResolver resolver =
            mock(ShoppingIntentResolver.class);

    private final ShoppingLineInterpreter interpreter =
            new ShoppingLineInterpreter(
                    new ShoppingListTextParser(),
                    resolver,
                    new ProductNameNormalizer()
            );

    private void kind(String written, String alias, boolean exact, String unit) {
        when(resolver.resolve(written)).thenReturn(Optional.of(
                new ShoppingIntentResolver.ResolvedShoppingIntent(
                        7L, "CODE", "Naziv", alias, exact, unit
                )
        ));
    }

    @Test
    void aBrandBesideTheKindOfProductNarrowsTheChoice() {
        kind("Pivo Zaječarsko", "pivo", false, "ml");
        when(resolver.isKnownBrand("Zaječarsko")).thenReturn(true);

        var line = interpreter.interpret("Pivo Zaječarsko 0.5");

        assertThat(line.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(line.name()).isEqualTo("Pivo Zaječarsko");
        assertThat(line.category()).isEqualTo("Pivo");
        assertThat(line.normalizedCategory()).isEqualTo("pivo");
        assertThat(line.requiredBrand()).isEqualTo("Zaječarsko");
        assertThat(line.targetQuantity()).isEqualByComparingTo("500");
        assertThat(line.baseUnit()).isEqualTo("ml");
    }

    @Test
    void aBareNumberIsLitresOnlyForSomethingSoldByVolume() {
        kind("Kisela voda", "kisela voda", true, "ml");
        var water = interpreter.interpret("Kisela voda 1.75");
        assertThat(water.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(water.targetQuantity()).isEqualByComparingTo("1750");

        kind("Vrat", "vrat", true, null);
        var neck = interpreter.interpret("Vrat 1,5");
        assertThat(neck.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
        assertThat(neck.name()).isEqualTo("Vrat 1,5");
        assertThat(neck.targetQuantity()).isNull();
    }

    @Test
    void wordsThatAreNotABrandNameOneProductAndKeepTheSize() {
        kind("Plantaže smederevka belo vino", "belo vino", false, "ml");
        when(resolver.isKnownBrand(anyString())).thenReturn(false);

        var wine = interpreter.interpret("Plantaže smederevka belo vino 1l");

        assertThat(wine.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
        assertThat(wine.name()).isEqualTo("Plantaže smederevka belo vino 1l");
        assertThat(wine.requiredBrand()).isNull();
    }

    @Test
    void aColourOfWineWithABrandIsThatBrandsWine() {
        kind("Rubin roze", "roze", false, "ml");
        when(resolver.isKnownBrand("Rubin")).thenReturn(true);

        var wine = interpreter.interpret("Rubin roze 1l");

        assertThat(wine.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(wine.category()).isEqualTo("roze");
        assertThat(wine.requiredBrand()).isEqualTo("Rubin");
        assertThat(wine.targetQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    void anUnknownLineIsAProductSearchWithEverythingThatWasWritten() {
        when(resolver.resolve(anyString())).thenReturn(Optional.empty());

        var plates = interpreter.interpret("2x Plastični tanjiri");

        assertThat(plates.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
        assertThat(plates.name()).isEqualTo("Plastični tanjiri");
    }

    @Test
    void theKindHasToBeWholeWords() {
        // "pivo" inside "Pivo-Zaječarsko" is still a word of its own, but a
        // brand glued to it is not a separate brand.
        kind("Zaječarsko pivoo", "pivo", false, "ml");
        when(resolver.isKnownBrand(anyString())).thenReturn(true);

        assertThat(interpreter.interpret("Zaječarsko pivoo").matchingRule())
                .isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
    }
}
