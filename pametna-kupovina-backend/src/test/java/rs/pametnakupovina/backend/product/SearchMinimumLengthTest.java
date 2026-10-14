package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.FieldRefusal;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.matching.SearchSpellingCorrector;
import rs.pametnakupovina.backend.matching.SearchWordVocabularyRepository;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "m" matched 98.449 products and took 6,9 s, so a search needs two letters
 * or digits before it reaches the catalogue.
 */
class SearchMinimumLengthTest {

    private final CanonicalProductSearchRepository repository =
            mock(CanonicalProductSearchRepository.class);
    private final ShoppingIntentResolver intentResolver =
            mock(ShoppingIntentResolver.class);
    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();
    private final CanonicalProductSearchService service =
            new CanonicalProductSearchService(
                    repository,
                    normalizer,
                    new ProductQuantityParser(),
                    new ProductMatchScorer(normalizer),
                    new EanValidator(),
                    intentResolver,
                    new SearchSpellingCorrector(
                            mock(SearchWordVocabularyRepository.class))
            );

    @Test
    void oneLetterIsRefusedBeforeTheCatalogueIsAsked() {
        for (String query : List.of("m", " m ", "m.", "š")) {
            assertThatThrownBy(() -> service.search(query, 0, 10, false))
                    .isInstanceOf(FieldRefusal.class)
                    .hasMessageContaining("bar 2 slova ili broja")
                    .extracting(e -> ((FieldRefusal) e).getField())
                    .isEqualTo("query");
        }
        verify(repository, never()).findCandidates(anyString(), any());
    }

    @Test
    void twoLettersAreSearched() {
        when(repository.findCandidates(anyString(), any())).thenReturn(List.of());
        when(intentResolver.resolve(anyString())).thenReturn(Optional.empty());

        assertThat(service.search("šu", 0, 10, false).items()).isEmpty();
        verify(repository, atLeastOnce()).findCandidates(anyString(), any());
    }
}
