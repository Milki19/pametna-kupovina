package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.util.List;
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

    @Test
    void aBareWholeNumberCountsPackages() {
        kind("mleko", "mleko", true, "ml");
        var milk = interpreter.interpret("mleko 2");
        assertThat(milk.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(milk.name()).isEqualTo("mleko");
        assertThat(milk.quantity()).isEqualByComparingTo("2");
        assertThat(milk.targetQuantity()).isNull();

        var counted = interpreter.interpret("2x mleko 3");
        assertThat(counted.quantity()).isEqualByComparingTo("6");
    }

    @Test
    void aNumberBesideOneProductStaysInItsName() {
        var plazma = interpreter.interpret("Plazma 300");
        assertThat(plazma.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
        assertThat(plazma.name()).isEqualTo("Plazma 300");
        assertThat(plazma.quantity()).isEqualByComparingTo("1");

        var days = interpreter.interpret("7 Days kroasan");
        assertThat(days.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
        assertThat(days.name()).isEqualTo("7 Days kroasan");
        assertThat(days.quantity()).isEqualByComparingTo("1");
    }

    @Test
    void eggsAreCountedOneByOneNotByTheCarton() {
        kind("jaja", "jaja", true, "piece");
        for (String written : List.of("jaja 10 kom", "10 jaja", "jaja 10")) {
            var eggs = interpreter.interpret(written);
            assertThat(eggs.quantity()).as(written).isEqualByComparingTo("1");
            assertThat(eggs.targetQuantity()).as(written).isEqualByComparingTo("10");
            assertThat(eggs.baseUnit()).as(written).isEqualTo("piece");
        }

        var cartons = interpreter.interpret("2x jaja");
        assertThat(cartons.quantity()).isEqualByComparingTo("2");
        assertThat(cartons.targetQuantity()).isNull();
    }

    @Test
    void aWordInTheGenitiveFindsItsKindOfProduct() {
        kind("mleko", "mleko", true, "ml");
        kind("krompir", "krompir", true, "g");
        kind("kisela voda", "kisela voda", true, "ml");

        var milk = interpreter.interpret("2 mleka");
        assertThat(milk.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(milk.name()).isEqualTo("mleko");
        assertThat(milk.quantity()).isEqualByComparingTo("2");

        var potatoes = interpreter.interpret("2 kg krompira");
        assertThat(potatoes.name()).isEqualTo("krompir");
        assertThat(potatoes.targetQuantity()).isEqualByComparingTo("2000");

        var water = interpreter.interpret("kisele vode");
        assertThat(water.matchingRule()).isEqualTo(ShoppingItemRule.EXACT_PRODUCT);
    }

    @Test
    void aNumberThatIsPartOfTheKindStaysInIt() {
        kind("3 u 1", "3 u 1", true, "g");
        var coffee = interpreter.interpret("3 u 1");
        assertThat(coffee.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(coffee.name()).isEqualTo("3 u 1");
        assertThat(coffee.quantity()).isEqualByComparingTo("1");
    }
}
