package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.matching.SearchSpellingCorrector;
import rs.pametnakupovina.backend.matching.SearchWordVocabularyRepository;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Twenty new searches at once on two cores all finished near the end
 * (proba 5.11.2026): the catalogue is asked by as many at once as there are
 * cores, and a failed lookup gives its turn back.
 */
class SearchInTurnTest {

    private final ProductNameNormalizer normalizer = new ProductNameNormalizer();
    private final CanonicalProductSearchService service = new CanonicalProductSearchService(
            mock(CanonicalProductSearchRepository.class),
            normalizer,
            new ProductQuantityParser(),
            new ProductMatchScorer(normalizer),
            new EanValidator(),
            mock(ShoppingIntentResolver.class),
            new SearchSpellingCorrector(mock(SearchWordVocabularyRepository.class)),
            new CatalogMemo(false)
    );

    @Test
    void noMoreLookupsAtOnceThanCores() throws Exception {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        var pool = Executors.newFixedThreadPool(cores * 3);
        for (int i = 0; i < cores * 6; i++) {
            pool.submit(() -> service.inTurn(() -> {
                most.accumulateAndGet(now.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return now.decrementAndGet();
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(most.get()).isBetween(1, cores);
    }

    @Test
    void aFailedLookupGivesItsTurnBack() {
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        for (int i = 0; i < cores + 1; i++) {
            assertThatThrownBy(() -> service.inTurn(() -> {
                throw new IllegalStateException("baza");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThat(service.inTurn(() -> "dalje")).isEqualTo("dalje");
    }
}
