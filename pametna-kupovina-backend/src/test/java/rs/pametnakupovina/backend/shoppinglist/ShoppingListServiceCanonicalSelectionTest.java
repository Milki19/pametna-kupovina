package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingListServiceCanonicalSelectionTest {

    private final ShoppingListRepository repository =
            mock(ShoppingListRepository.class);
    private final ShoppingListClientTokenPolicy tokenPolicy =
            mock(ShoppingListClientTokenPolicy.class);
    private final ShoppingListTextParser textParser =
            mock(ShoppingListTextParser.class);
    private final ProductNameNormalizer normalizer =
            mock(ProductNameNormalizer.class);

    private ShoppingListService service;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(
                repository,
                tokenPolicy,
                textParser,
                normalizer
        );
        when(tokenPolicy.validateAndHash("client-token"))
                .thenReturn("client-token-hash");
        when(repository.existsByIdAndClientTokenHash(
                7L,
                "client-token-hash"
        )).thenReturn(true);
    }

    @Test
    void selectedCanonicalProductWithoutBarcodeIsConfirmedDirectly() {
        when(repository.findCanonicalProductById(42L))
                .thenReturn(Optional.of(
                        new CanonicalProductReference(42L, null)
                ));

        service.addItem(
                7L,
                "client-token",
                new AddShoppingListItemRequest(
                        "Proizvod bez barkoda",
                        "unos korisnika",
                        null,
                        42L,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT,
                        null
                )
        );

        verify(repository).addItem(
                eq(7L),
                eq("Proizvod bez barkoda"),
                eq("unos korisnika"),
                isNull(),
                eq(42L),
                eq(BigDecimal.ONE),
                eq(ShoppingItemRule.EXACT_PRODUCT),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull()
        );
    }

    @Test
    void unknownCanonicalProductIsRejectedBeforeInsert() {
        when(repository.findCanonicalProductById(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addItem(
                7L,
                "client-token",
                new AddShoppingListItemRequest(
                        "Nepostojeći proizvod",
                        null,
                        null,
                        999L,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT,
                        null
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Canonical proizvod nije pronađen");

        verify(repository, never()).touch(7L);
    }

    @Test
    void barcodeMustBelongToSelectedCanonicalProduct() {
        when(repository.findCanonicalProductById(42L))
                .thenReturn(Optional.of(
                        new CanonicalProductReference(
                                42L,
                                "3838600041300"
                        )
                ));

        assertThatThrownBy(() -> service.addItem(
                7L,
                "client-token",
                new AddShoppingListItemRequest(
                        "Pogrešan barkod",
                        null,
                        "8600000000000",
                        42L,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT,
                        null
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "Barkod ne pripada izabranom canonical proizvodu"
                );
    }

    @Test
    void flexibleItemCannotSelectCanonicalProduct() {
        assertThatThrownBy(() -> service.addItem(
                7L,
                "client-token",
                new AddShoppingListItemRequest(
                        "Voda",
                        null,
                        null,
                        42L,
                        BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints(
                                "voda",
                                null,
                                null,
                                null,
                                null
                        )
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(
                        "Canonical proizvod važi samo za tačnu stavku"
                );
    }
}
