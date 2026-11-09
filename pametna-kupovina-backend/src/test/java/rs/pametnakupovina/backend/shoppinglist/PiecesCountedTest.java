package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fruit and vegetables counted are pieces, not kilograms: "limun 3" was 3 kg
 * and "avokado 2" 1,5 kg (šesti test, 6.11.2026). Only a word whose piece has
 * a mass (V156) is counted so.
 */
class PiecesCountedTest {

    private final ShoppingIntentResolver resolver = mock(ShoppingIntentResolver.class);

    private final ShoppingLineInterpreter interpreter = new ShoppingLineInterpreter(
            new ShoppingListTextParser(), resolver, new ProductNameNormalizer(),
            word -> Optional.ofNullable(Map.of("limun", new BigDecimal("100"),
                    "avokado", new BigDecimal("200")).get(word)));

    {
        when(resolver.resolveForms(anyString())).thenReturn(Optional.empty());
    }

    private void kind(String written, String alias) {
        when(resolver.resolve(written)).thenReturn(Optional.of(
                new ShoppingIntentResolver.ResolvedShoppingIntent(3L, "FRUIT", "Voće", alias, true, null)));
    }

    @Test
    void aCountOfLemonsIsPieces() {
        kind("limun", "limun");
        for (String line : new String[]{"limun 3", "3x limun", "limun 3 kom", "3 kom limun"}) {
            var lemons = interpreter.interpret(line);
            assertThat(lemons.targetQuantity()).as(line).isEqualByComparingTo("3");
            assertThat(lemons.baseUnit()).as(line).isEqualTo("piece");
            assertThat(lemons.quantity()).as(line).isEqualByComparingTo("1");
        }
        var one = interpreter.interpret("limun 1 kom");
        assertThat(one.targetQuantity()).isEqualByComparingTo("1");
        assertThat(one.baseUnit()).isEqualTo("piece");
    }

    @Test
    void noCountOrAWeightStaysAsItWas() {
        kind("limun", "limun");
        var plain = interpreter.interpret("limun");
        assertThat(plain.targetQuantity()).isNull();
        assertThat(plain.quantity()).isEqualByComparingTo("1");

        var kilos = interpreter.interpret("limun 2kg");
        assertThat(kilos.targetQuantity()).isEqualByComparingTo("2000");
        assertThat(kilos.baseUnit()).isEqualTo("g");
    }

    @Test
    void aWordWithoutAPieceMassKeepsItsCount() {
        kind("grozdje", "grozdje");
        var grapes = interpreter.interpret("grozdje 2");
        assertThat(grapes.targetQuantity()).isNull();
        assertThat(grapes.quantity()).isEqualByComparingTo("2");
    }

    @Test
    void theNoteSaysHowManyKilogramsThePiecesAre() {
        ShoppingListItemResponse lemons = item("piece", "3");
        assertThat(ShoppingRecommendationService.piecesWeighedNote(lemons, offer("g", "300")))
                .isEqualTo("3 kom ≈ 0,3 kg.");
        assertThat(ShoppingRecommendationService.piecesWeighedNote(item("piece", "2"), offer("g", "1400")))
                .isEqualTo("2 kom ≈ 1,4 kg.");
        // Sold by the piece, the line already says it.
        assertThat(ShoppingRecommendationService.piecesWeighedNote(lemons, offer("piece", "3"))).isNull();
        assertThat(ShoppingRecommendationService.piecesWeighedNote(item("g", "300"), offer("g", "300"))).isNull();
    }

    private static ShoppingListItemResponse item(String unit, String target) {
        return new ShoppingListItemResponse(1L, "limun", "limun " + target, null, BigDecimal.ONE,
                ShoppingItemRule.FLEXIBLE_CATEGORY, ShoppingItemMatchingStatus.CONFIRMED, null, null, null,
                null, null,
                new FlexibleItemConstraints("limun", null, null, null, unit, new BigDecimal(target)),
                null, null);
    }

    private static StoreItemOffer offer(String unit, String target) {
        BigDecimal price = new BigDecimal("129.99");
        return new StoreItemOffer(1L, "T", "Test", "S", "Standard", "Radnja", "Adresa", "Beograd", 44.0, 20.0,
                1L, "limun", BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY,
                ShoppingItemMatchingStatus.CONFIRMED, 1L, null, "LIMUN", null, null,
                LocalDate.of(2026, 11, 8), price, null, price, price, "RETAILER",
                new PurchaseQuantity(new BigDecimal("0.3"), new BigDecimal("1000"), unit,
                        new BigDecimal(target), new BigDecimal(target), BigDecimal.ZERO, price),
                true, false);
    }
}
