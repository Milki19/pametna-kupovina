package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.FieldRefusal;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "999999x hleb" priced a list at 39 million and a 150-letter name was kept
 * as typed. A refused field is named, so the app shows the reason under it.
 */
class ShoppingListItemValidationTest {

    private static final long ACCOUNT = 3L;

    private final ShoppingListRepository repository =
            mock(ShoppingListRepository.class);

    private final ShoppingListTextParser parser =
            mock(ShoppingListTextParser.class);

    private final ShoppingLineInterpreter interpreter =
            mock(ShoppingLineInterpreter.class);

    private ShoppingListService service;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(
                repository,
                parser,
                mock(ProductNameNormalizer.class),
                mock(ShoppingIntentResolver.class),
                interpreter
        );
        when(repository.existsByIdAndAccount(7L, ACCOUNT)).thenReturn(true);
    }

    @Test
    void quantityOutsideAHundredthToNineHundredNinetyNineNamesTheField() {
        for (String quantity : new String[]{"0", "-5", "0.009", "999.01", "999999"}) {
            assertThatThrownBy(() -> service.addItem(7L, ACCOUNT, typed("hleb", quantity)))
                    .isInstanceOf(FieldRefusal.class)
                    .hasMessageContaining("od 0,01 do 999")
                    .extracting(e -> ((FieldRefusal) e).getField())
                    .isEqualTo("quantity");
            assertThatThrownBy(() -> service.updateItem(7L, 1L, ACCOUNT, edited("hleb", quantity)))
                    .isInstanceOf(FieldRefusal.class)
                    .extracting(e -> ((FieldRefusal) e).getField())
                    .isEqualTo("quantity");
        }
    }

    @Test
    void aNameOfOnlyEmojiIsEmptyAndNamesTheField() {
        assertThatThrownBy(() -> service.addItem(7L, ACCOUNT, typed("🍅 ✔️", "1")))
                .isInstanceOf(FieldRefusal.class)
                .extracting(e -> ((FieldRefusal) e).getField())
                .isEqualTo("name");
    }

    @Test
    void theEdgesOfTheRangeAreAccepted() {
        assertThatCode(() -> service.addItem(7L, ACCOUNT, typed("hleb", "0.01")))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.addItem(7L, ACCOUNT, typed("hleb", "999")))
                .doesNotThrowAnyException();
    }

    @Test
    void aTypedNameLongerThanEightyLettersNamesTheField() {
        assertThatThrownBy(() -> service.addItem(7L, ACCOUNT, typed("a".repeat(81), "1")))
                .isInstanceOf(FieldRefusal.class)
                .hasMessageContaining("80 znakova")
                .extracting(e -> ((FieldRefusal) e).getField())
                .isEqualTo("name");
        assertThatCode(() -> service.addItem(7L, ACCOUNT, typed("a".repeat(80), "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void aTypedNameWithoutALetterNamesTheField() {
        for (String name : new String[]{"%%%###", "!!!", "123", "-- ..."}) {
            assertThatThrownBy(() -> service.addItem(7L, ACCOUNT, typed(name, "1")))
                    .isInstanceOf(FieldRefusal.class)
                    .hasMessageContaining("bar jedno slovo")
                    .extracting(e -> ((FieldRefusal) e).getField())
                    .isEqualTo("name");
            assertThatThrownBy(() -> service.updateItem(7L, 1L, ACCOUNT, edited(name, "1")))
                    .isInstanceOf(FieldRefusal.class)
                    .extracting(e -> ((FieldRefusal) e).getField())
                    .isEqualTo("name");
        }
        assertThatCode(() -> service.addItem(7L, ACCOUNT, typed("7up", "1")))
                .doesNotThrowAnyException();
        assertThatCode(() -> service.addItem(7L, ACCOUNT, typed("Čaj", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void aPastedLineWithoutALetterIsRejectedAndTheRestIsAdded() {
        String text = "mleko\n%%%###\n!!!";
        ParsedShoppingListLine milk = line("mleko");
        ParsedShoppingListLine signs = line("%%%###");
        ParsedShoppingListLine bangs = line("!!!");
        when(parser.parse(text)).thenReturn(
                new ParsedShoppingListText(List.of(milk, signs, bangs), 0));
        for (ParsedShoppingListLine each : List.of(milk, signs, bangs)) {
            when(interpreter.interpret(each)).thenReturn(interpreted(each.name()));
        }

        PasteShoppingListItemsResponse response =
                service.addPastedItems(7L, ACCOUNT, new PasteShoppingListItemsRequest(text));

        assertThat(response.createdCount()).isEqualTo(1);
        assertThat(response.rejectedLines())
                .extracting(PasteShoppingListItemsResponse.RejectedLine::text)
                .containsExactly("%%%###", "!!!");
        assertThat(response.rejectedLines())
                .extracting(PasteShoppingListItemsResponse.RejectedLine::field)
                .containsOnly("name");
    }

    @Test
    void aPasteOfOnlySignsAddsNothingAndSaysWhy() {
        String text = "%%%###";
        ParsedShoppingListLine signs = line(text);
        when(parser.parse(text)).thenReturn(new ParsedShoppingListText(List.of(signs), 0));
        when(interpreter.interpret(signs)).thenReturn(interpreted(text));

        assertThatThrownBy(() -> service.addPastedItems(
                7L, ACCOUNT, new PasteShoppingListItemsRequest(text)))
                .isInstanceOf(FieldRefusal.class)
                .hasMessageContaining("bar jedno slovo");
    }

    private static ParsedShoppingListLine line(String text) {
        return new ParsedShoppingListLine(text, text, BigDecimal.ONE, null, null);
    }

    private static ShoppingLineInterpreter.InterpretedLine interpreted(String name) {
        return new ShoppingLineInterpreter.InterpretedLine(
                name, BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY, name, name,
                null, null, null, null);
    }

    @Test
    void aProductFromTheCatalogueKeepsItsLongerName() {
        when(repository.findCanonicalProductById(42L))
                .thenReturn(Optional.of(new CanonicalProductReference(42L, null)));

        assertThatCode(() -> service.addItem(7L, ACCOUNT, new AddShoppingListItemRequest(
                "Proizvod iz kataloga sa dugim nazivom ".repeat(3),
                null,
                null,
                42L,
                BigDecimal.ONE,
                ShoppingItemRule.EXACT_PRODUCT,
                null
        ))).doesNotThrowAnyException();
    }

    @Test
    void aPastedTotalAboveNineHundredNinetyNineIsRefusedLikeACount() {
        // "mleko 999999" reads as 999999 l of milk.
        assertThatThrownBy(() -> paste("mleko 999999", new BigDecimal("999999000"), "ml"))
                .isInstanceOf(FieldRefusal.class)
                .hasMessageContaining("od 0,01 do 999");
        assertThatThrownBy(() -> paste("jaja 5000 kom", new BigDecimal("5000"), "piece"))
                .isInstanceOf(FieldRefusal.class)
                .hasMessageContaining("od 0,01 do 999");
    }

    @Test
    void aPastedTotalOfNineHundredNinetyNineIsAccepted() {
        assertThatCode(() -> paste("mleko 999 l", new BigDecimal("999000"), "ml"))
                .doesNotThrowAnyException();
        assertThatCode(() -> paste("jaja 999 kom", new BigDecimal("999"), "piece"))
                .doesNotThrowAnyException();
    }

    private void paste(String text, BigDecimal target, String unit) {
        ParsedShoppingListLine line = new ParsedShoppingListLine(
                "mleko", text, BigDecimal.ONE, target, unit);
        when(parser.parse(text)).thenReturn(new ParsedShoppingListText(List.of(line), 0));
        when(interpreter.interpret(line)).thenReturn(new ShoppingLineInterpreter.InterpretedLine(
                "mleko", BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY, "mleko", "mleko",
                1L, null, target, unit));
        service.addPastedItems(7L, ACCOUNT, new PasteShoppingListItemsRequest(text));
    }

    private static AddShoppingListItemRequest typed(String name, String quantity) {
        return new AddShoppingListItemRequest(name, null, new BigDecimal(quantity));
    }

    private static UpdateShoppingListItemRequest edited(String name, String quantity) {
        return new UpdateShoppingListItemRequest(name, null, new BigDecimal(quantity));
    }
}
