package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "kese za smeće 60l" had no price after V166: a bag's litres are what it
 * holds, so no bag is sold in litres, and the line asked for 60 l of bags
 * (proba V166, 11.11.2026). For products that hold what they say the size is
 * a word of the name, and the line buys one pack.
 */
class BagSizeIsANameTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();

    private final ShoppingIntentResolver resolver = mock(ShoppingIntentResolver.class);

    private final ShoppingLineInterpreter interpreter =
            new ShoppingLineInterpreter(new ShoppingListTextParser(), resolver, normalizer);

    {
        when(resolver.resolveForms(anyString())).thenReturn(Optional.empty());
        when(resolver.productsMatch(anyString())).thenReturn(true);
    }

    @Test
    void binBagLitresAreASizeTheNameSays() {
        when(resolver.namesHoldTheirSize(anyString())).thenReturn(true);

        var bags = interpreter.interpret("kese za smeće 60l");

        assertThat(bags.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(bags.category()).isEqualTo("kese za smeće 60 l");
        assertThat(bags.targetQuantity()).isNull();
        assertThat(bags.baseUnit()).isNull();
        assertThat(bags.quantity()).isEqualByComparingTo("1");

        String wanted = WantedWords.pattern(bags.category(), null, normalizer, Map.of(), true);
        assertThat(wanted).endsWith("(?=.*\\m60 (l|lit[a-z]*)\\M)");
        // Without the size kept, the same words ask only for bin bags.
        assertThat(WantedWords.pattern(bags.category(), null, normalizer, Map.of(), false))
                .doesNotContain("60");
    }

    @Test
    void litresOfWhatIsSoldInLitresStayAnAmount() {
        when(resolver.namesHoldTheirSize(anyString())).thenReturn(false);

        var juice = interpreter.interpret("sok od jabuke 2l");

        assertThat(juice.targetQuantity()).isEqualByComparingTo("2000");
        assertThat(juice.baseUnit()).isEqualTo("ml");
        assertThat(juice.category()).doesNotContain("2");
    }

    @Test
    void freezerBagLitresAreASizeOfTheKind() {
        when(resolver.resolve("kese za zamrzivač")).thenReturn(Optional.of(
                new ShoppingIntentResolver.ResolvedShoppingIntent(
                        9L, "FREEZER_BAG", "Kese za zamrzivač", "kese za zamrzivac", true, null)));
        when(resolver.kindHoldsItsSize(9L)).thenReturn(true);

        var bags = interpreter.interpret("kese za zamrzivač 3l");

        assertThat(bags.shoppingIntentId()).isEqualTo(9L);
        assertThat(bags.category()).isEqualTo("kese za zamrzivač 3 l");
        assertThat(bags.targetQuantity()).isNull();
        assertThat(WantedWords.pattern(bags.category(), "kese za zamrzivac", normalizer, Map.of(), true))
                .isEqualTo("\\m3 (l|lit[a-z]*)\\M");
    }

    @Test
    void aKilogramOfSugarIsStillAKilogram() {
        when(resolver.resolve("šećer")).thenReturn(Optional.of(
                new ShoppingIntentResolver.ResolvedShoppingIntent(
                        11L, "SUGAR", "Šećer", "secer", true, "g")));
        when(resolver.kindHoldsItsSize(11L)).thenReturn(false);

        var sugar = interpreter.interpret("šećer 1kg");

        assertThat(sugar.category()).isEqualTo("šećer");
        assertThat(sugar.targetQuantity()).isEqualByComparingTo("1000");
        assertThat(sugar.baseUnit()).isEqualTo("g");
    }

    @Test
    void aSizeIsWrittenAsTheNamesWriteIt() {
        assertThat(ShoppingLineInterpreter.sizeWords(new BigDecimal("60000"), "ml")).isEqualTo("60 l");
        assertThat(ShoppingLineInterpreter.sizeWords(new BigDecimal("1500"), "ml")).isEqualTo("1,5 l");
        assertThat(ShoppingLineInterpreter.sizeWords(new BigDecimal("500"), "ml")).isEqualTo("500 ml");
        assertThat(ShoppingLineInterpreter.sizeWords(new BigDecimal("3000"), "g")).isEqualTo("3 kg");
        assertThat(ShoppingLineInterpreter.sizeWords(new BigDecimal("250"), "g")).isEqualTo("250 g");
    }
}
