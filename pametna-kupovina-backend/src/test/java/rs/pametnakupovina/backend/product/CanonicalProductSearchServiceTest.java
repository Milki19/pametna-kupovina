package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver.ResolvedShoppingIntent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "mleko" used to rank chocolate milk and a body lotion above milk, because
 * their names were a little closer to the query. Milk now leads whenever the
 * query asks for nothing more than milk, and the text decides otherwise.
 */
class CanonicalProductSearchServiceTest {

    private static final long MILK = 1L;
    private static final long FLAVORED_MILK = 2L;

    private final CanonicalProductSearchRepository repository =
            mock(CanonicalProductSearchRepository.class);
    private final ShoppingIntentResolver intentResolver =
            mock(ShoppingIntentResolver.class);
    private final ProductNameNormalizer normalizer =
            new ProductNameNormalizer();
    private final CanonicalProductSearchService service =
            new CanonicalProductSearchService(
                    repository,
                    normalizer,
                    new ProductQuantityParser(),
                    new ProductMatchScorer(normalizer),
                    new EanValidator(),
                    intentResolver
            );

    @BeforeEach
    void milkIsAShoppingIntent() {
        when(repository.findCandidates(anyString(), any())).thenReturn(List.of(
                row(10L, "NIVEA MLEKO 250ML", "0.3300", null),
                row(11L, "MLEKO COKO 1% 1L", "0.3200", FLAVORED_MILK),
                row(12L, "Alpsko mleko 3,5%mm 1L", "0.2800", MILK)
        ));
        when(repository.findProductTypePriorities(7L))
                .thenReturn(Map.of(MILK, 10));
        when(intentResolver.resolve(anyString())).thenReturn(Optional.of(
                new ResolvedShoppingIntent(7L, "MILK", "Mleko", "mleko", false)
        ));
    }

    @Test
    void milkLeadsWhenTheQueryAsksOnlyForMilk() {
        assertThat(names(service.search("mleko", 0, 10, false)))
                .startsWith("Alpsko mleko 3,5%mm 1L");
        assertThat(names(service.search("Mleko 1l", 0, 10, false)))
                .startsWith("Alpsko mleko 3,5%mm 1L");
    }

    @Test
    void aQueryThatSaysMoreThanTheTypeIsLeftToTheText() {
        assertThat(names(service.search("cokoladno mleko", 0, 10, false)))
                .containsExactly(
                        "NIVEA MLEKO 250ML",
                        "MLEKO COKO 1% 1L",
                        "Alpsko mleko 3,5%mm 1L"
                );
    }

    private static List<String> names(CanonicalProductSearchPage page) {
        return page.items().stream()
                .map(CanonicalProductSearchItem::name)
                .toList();
    }

    private static CanonicalProductSearchRow row(
            long familyId,
            String name,
            String similarity,
            Long productTypeId
    ) {
        return new CanonicalProductSearchRow(
                familyId,
                familyId + 100,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                new BigDecimal(similarity),
                false,
                true,
                productTypeId,
                1
        );
    }
}
