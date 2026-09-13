package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A grill list says how much meat is needed in total ("ćevapi 3kg"), never how
 * many packs, because the pack size differs per shop. Counting words keep
 * meaning packs.
 */
class ShoppingListTextParserTest {

    private final ShoppingListTextParser parser = new ShoppingListTextParser();

    private ParsedShoppingListLine parseOne(String line) {
        List<ParsedShoppingListLine> items = parser.parse(line).items();
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    @Test
    void weightBecomesATotalAmountAndLeavesTheNameSearchable() {
        ParsedShoppingListLine cevapi = parseOne("Ćevapi 3kg");
        assertThat(cevapi.name()).isEqualTo("Ćevapi");
        assertThat(cevapi.targetQuantity()).isEqualByComparingTo("3000");
        assertThat(cevapi.baseUnit()).isEqualTo("g");
        assertThat(cevapi.quantity()).isEqualByComparingTo("1");
    }

    @Test
    void volumeConvertsToMillilitresAndSpelledOutUnitsAreUnderstood() {
        assertThat(parseOne("mleko 1l").targetQuantity())
                .isEqualByComparingTo("1000");
        assertThat(parseOne("mleko 1l").baseUnit()).isEqualTo("ml");
        assertThat(parseOne("sok 500ml").targetQuantity())
                .isEqualByComparingTo("500");

        ParsedShoppingListLine sir = parseOne("Sitan sir 400grama");
        assertThat(sir.name()).isEqualTo("Sitan sir");
        assertThat(sir.targetQuantity()).isEqualByComparingTo("400");
        assertThat(sir.baseUnit()).isEqualTo("g");
    }

    @Test
    void decimalsAndLeadingAmountsWork() {
        assertThat(parseOne("Vrat 1,5 kg").targetQuantity())
                .isEqualByComparingTo("1500");
        ParsedShoppingListLine leading = parseOne("2kg krilca");
        assertThat(leading.name()).isEqualTo("krilca");
        assertThat(leading.targetQuantity()).isEqualByComparingTo("2000");
    }

    @Test
    void countingWordsStillMeanPackagesNotWeight() {
        ParsedShoppingListLine pieces = parseOne("3x mleko");
        assertThat(pieces.quantity()).isEqualByComparingTo("3");
        assertThat(pieces.targetQuantity()).isNull();

        ParsedShoppingListLine suffix = parseOne("jaja 2 kom");
        assertThat(suffix.quantity()).isEqualByComparingTo("2");
        assertThat(suffix.targetQuantity()).isNull();
    }

    @Test
    void aPlainNameCarriesNoAmountAtAll() {
        ParsedShoppingListLine plain = parseOne("kiselo mleko");
        assertThat(plain.name()).isEqualTo("kiselo mleko");
        assertThat(plain.targetQuantity()).isNull();
        assertThat(plain.quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void anAmountWithNoProductNameIsNotAnItem() {
        assertThat(parseOne("3kg").name()).isEqualTo("3kg");
        assertThat(parseOne("3kg").targetQuantity()).isNull();
    }
}
