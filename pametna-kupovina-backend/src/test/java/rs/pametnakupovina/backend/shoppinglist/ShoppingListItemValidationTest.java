package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.FieldRefusal;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.Optional;

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

    private ShoppingListService service;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(
                repository,
                mock(ShoppingListTextParser.class),
                mock(ProductNameNormalizer.class),
                mock(ShoppingIntentResolver.class),
                mock(ShoppingLineInterpreter.class)
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

    private static AddShoppingListItemRequest typed(String name, String quantity) {
        return new AddShoppingListItemRequest(name, null, new BigDecimal(quantity));
    }

    private static UpdateShoppingListItemRequest edited(String name, String quantity) {
        return new UpdateShoppingListItemRequest(name, null, new BigDecimal(quantity));
    }
}
