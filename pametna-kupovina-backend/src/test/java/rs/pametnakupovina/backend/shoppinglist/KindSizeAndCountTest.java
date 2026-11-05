package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lines from the fifth anonymous test (1.11.2026): a size written as a
 * letter, a count of rolls at the end, a count after a kind the dictionary
 * now knows, a word that makes a name something else, and the kind shown
 * in the shopper's own words.
 */
class KindSizeAndCountTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();

    private final ShoppingIntentResolver resolver = mock(ShoppingIntentResolver.class);

    private final ShoppingLineInterpreter interpreter =
            new ShoppingLineInterpreter(new ShoppingListTextParser(), resolver, normalizer);

    {
        when(resolver.resolveForms(anyString())).thenReturn(Optional.empty());
    }

    private void kind(String written, String alias, boolean exact, String unit) {
        when(resolver.resolve(written)).thenReturn(Optional.of(
                new ShoppingIntentResolver.ResolvedShoppingIntent(7L, "CODE", "Naziv", alias, exact, unit)
        ));
    }

    @Test
    void aSizeLetterIsWhatTheLineAsksBeyondTheKind() {
        String pattern = WantedWords.pattern("jaja L", "jaja", normalizer);

        assertThat(pattern).isEqualTo(WantedWords.SIZE_GUARD + "(l|large)\\M");
        // After a number the same letter is a litre, not a size.
        assertThat(WantedWords.pattern("mleko 1 l", "mleko", normalizer)).isNull();
        assertThat(WantedWords.withoutSizeLetters("jaja L")).isEqualTo("jaja");
        assertThat(WantedWords.withoutSizeLetters("jaja XL")).isEqualTo("jaja");
        assertThat(WantedWords.withoutSizeLetters("mleko 1 l")).isEqualTo("mleko 1 l");
    }

    @Test
    void largeEggsStayEggsWhereNoShopWritesTheSize() {
        // "6 jaja L" became six organic eggs for 400 RSD: with no egg named
        // "L" the line was read as any product named "jaja".
        kind("jaja L", "jaja", false, "piece");
        when(resolver.kindHasProductsMatching(anyLong(), anyString())).thenReturn(false);

        var eggs = interpreter.interpret("6 jaja L");

        assertThat(eggs.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(eggs.shoppingIntentId()).isEqualTo(7L);
        assertThat(eggs.category()).isEqualTo("jaja L");
        assertThat(eggs.quantity()).isEqualByComparingTo("1");
        assertThat(eggs.targetQuantity()).isEqualByComparingTo("6");
        assertThat(eggs.baseUnit()).isEqualTo("piece");
    }

    @Test
    void nothingBeyondTheKindIsStillTheKind() {
        kind("jaja kom", "jaja", false, "piece");

        var eggs = interpreter.interpret("jaja kom");

        assertThat(eggs.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(eggs.shoppingIntentId()).isEqualTo(7L);
    }

    @Test
    void aCountOfRollsIsThePackNotTheWordRolls() {
        String pattern = WantedWords.pattern("toalet papir 10 rolni", "toalet papir", normalizer);

        assertThat(pattern).isEqualTo("\\m10 (1|rol[a-z]*|kom[a-z]*)\\M");

        kind("toalet papir 10 rolni", "toalet papir", false, null);
        when(resolver.kindHasProductsMatching(7L, pattern)).thenReturn(true);

        var paper = interpreter.interpret("toalet papir 10 rolni");

        assertThat(paper.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(paper.shoppingIntentId()).isEqualTo(7L);
        assertThat(paper.quantity()).isEqualByComparingTo("1");
        assertThat(paper.category()).isEqualTo("toalet papir 10 rolni");
    }

    @Test
    void aCountAfterAKnownKindCountsCans() {
        kind("energetsko piće", "energetsko pice", true, "ml");

        var cans = interpreter.interpret("energetsko piće 4");

        assertThat(cans.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(cans.name()).isEqualTo("energetsko piće");
        assertThat(cans.quantity()).isEqualByComparingTo("4");
        assertThat(cans.targetQuantity()).isNull();
    }

    @Test
    void aSalamiNamedForACheeseIsNoCheese() {
        String pattern = WantedWords.pattern("parmezan", null, normalizer);

        assertThat(pattern).contains("salam[a-z]*").contains("kobasic[a-z]*").contains("ukus");
        // Asked for, the word is no longer something else.
        assertThat(WantedWords.pattern("parmezan salama", null, normalizer)).doesNotContain("salam[a-z]*|");
    }

    @Test
    void theKindIsShownInTheShoppersWords() {
        // "3 l mleka 2,8%" was shown as "Kategorija mleko 2 8".
        when(resolver.resolve(anyString())).thenReturn(Optional.empty());
        when(resolver.resolveForms("mleka 2,8%")).thenReturn(Optional.of(new ShoppingIntentResolver.FormMatch(
                new ShoppingIntentResolver.ResolvedShoppingIntent(7L, "MILK", "Mleko", "mleko", false, "ml"),
                "mleko 2 8")));
        when(resolver.kindHasProductsMatching(anyLong(), anyString())).thenReturn(true);

        var milk = interpreter.interpret("3 l mleka 2,8%");

        assertThat(milk.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(milk.category()).isEqualTo("mleko 2,8%");
        assertThat(normalizer.normalize(milk.category())).isEqualTo("mleko 2 8");
    }
    @Test
    void aFatTheMilksSayIsNoSize() {
        // "mleko 2,8" was read as 2,8 l of any milk (provera uživo 5.11.2026).
        kind("mleko", "mleko", true, "ml");
        when(resolver.resolve("mleko 2,8%")).thenReturn(Optional.empty());
        when(resolver.resolveForms("mleko 2,8%")).thenReturn(Optional.of(new ShoppingIntentResolver.FormMatch(
                new ShoppingIntentResolver.ResolvedShoppingIntent(7L, "MILK", "Mleko", "mleko", false, "ml"),
                "mleko 2 8")));
        when(resolver.kindSaysPercent(eq(7L), argThat(n -> n.compareTo(new BigDecimal("2.8")) == 0)))
                .thenReturn(true);
        when(resolver.kindHasProductsMatching(anyLong(), anyString())).thenReturn(true);

        var milk = interpreter.interpret("2x mleko 2,8");

        assertThat(milk.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(milk.category()).isEqualTo("mleko 2,8%");
        assertThat(milk.quantity()).isEqualByComparingTo("2");
        assertThat(milk.targetQuantity()).isNull();
    }

    @Test
    void aSizeTheKindIsSoldInStaysASize() {
        // "kisela voda 1,5" and "pivo 0,5": no percentage, so litres.
        kind("kisela voda", "kisela voda", true, "ml");

        var water = interpreter.interpret("kisela voda 1,5");

        assertThat(water.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(water.targetQuantity()).isEqualByComparingTo("1500");
        assertThat(water.baseUnit()).isEqualTo("ml");
    }
}
