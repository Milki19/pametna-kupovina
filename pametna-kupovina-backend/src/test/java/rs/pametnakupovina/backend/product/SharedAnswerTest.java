package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.matching.SearchSpellingCorrector;
import rs.pametnakupovina.backend.matching.SearchWordVocabularyRepository;
import rs.pametnakupovina.backend.privacy.PreciseLocation;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Šesti test (6.11.2026): every phone paid its own first search, because
 * only the phone kept the answer. The server now keeps the whole answer for
 * everyone five minutes, the place rounded to about a kilometre.
 */
class SharedAnswerTest {

    private final MovableClock clock = new MovableClock();
    private final CatalogMemo memo = new CatalogMemo(true, clock);
    private final CanonicalProductSearchRepository repository = mock(CanonicalProductSearchRepository.class);
    private final ShoppingIntentResolver intentResolver = mock(ShoppingIntentResolver.class);
    private final SearchWordVocabularyRepository vocabulary = mock(SearchWordVocabularyRepository.class);
    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();
    private final CanonicalProductSearchService service = new CanonicalProductSearchService(
            repository, normalizer, new ProductQuantityParser(), new ProductMatchScorer(normalizer),
            new EanValidator(), intentResolver, new SearchSpellingCorrector(vocabulary), memo);

    SharedAnswerTest() {
        when(repository.findCandidates(anyString(), any())).thenReturn(List.of(
                new CanonicalProductSearchRow(12L, 112L, "Alpsko mleko 3,5%mm 1L", null, null, null, null,
                        null, null, 1, new BigDecimal("0.2800"), false, true, null, 1)));
        when(intentResolver.resolve(anyString())).thenReturn(Optional.empty());
        when(vocabulary.findWordsOfLength(anyInt(), anyInt())).thenReturn(List.of());
        when(repository.nearestChainMeters(anyDouble(), anyDouble())).thenReturn(Map.of());
    }

    private CanonicalProductSearchPage search(PreciseLocation near) {
        return service.search("mleko", 0, 10, false, near, false);
    }

    @Test
    void theSameWordsFromTheSameNeighbourhoodAreWorkedOutOnce() {
        CanonicalProductSearchPage first = search(new PreciseLocation(44.81, 20.46));
        CanonicalProductSearchPage second = search(new PreciseLocation(44.81, 20.46));
        search(null);
        search(null);

        assertThat(second).isSameAs(first);
        verify(repository, times(2)).findAvailability(anyList());
    }

    @Test
    void anotherNeighbourhoodOrPageIsItsOwnAnswer() {
        search(new PreciseLocation(44.81, 20.46));
        search(new PreciseLocation(44.82, 20.46));
        service.search("mleko", 1, 10, false, new PreciseLocation(44.81, 20.46), false);
        service.search("mleko", 0, 10, false, new PreciseLocation(44.81, 20.46), true);

        verify(repository, times(4)).findAvailability(anyList());
    }

    @Test
    void anExactPlaceIsNeverKept() {
        search(new PreciseLocation(44.8125, 20.4612));
        search(new PreciseLocation(44.8125, 20.4612));

        verify(repository, times(2)).findAvailability(anyList());
        verify(repository, times(2)).nearestChainMeters(anyDouble(), anyDouble());
        assertThat(memo.sharedSize()).isZero();
    }

    @Test
    void fiveMinutesLaterItIsWorkedOutAgain() {
        search(new PreciseLocation(44.81, 20.46));
        clock.move(Duration.ofMinutes(4).plusSeconds(59));
        search(new PreciseLocation(44.81, 20.46));
        clock.move(Duration.ofSeconds(1));
        search(new PreciseLocation(44.81, 20.46));

        verify(repository, times(2)).findAvailability(anyList());
    }

    @Test
    void aPlaceIsGoneAfterFiveMinutesEvenIfNobodyAsksAgain() {
        search(new PreciseLocation(44.81, 20.46));
        assertThat(memo.sharedSize()).isPositive();

        clock.move(Duration.ofMinutes(5));
        memo.forgetExpiredShared();

        assertThat(memo.sharedSize()).isZero();
    }

    @Test
    void anImportOrANewSalesListStartsOver() {
        search(null);
        memo.catalogChanged();
        search(null);
        memo.salesChanged();
        search(null);
        memo.offersChanged();
        search(null);

        verify(repository, times(4)).findAvailability(anyList());
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T10:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
