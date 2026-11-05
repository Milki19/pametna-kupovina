package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Proba 5.11.2026: after a restart twenty new searches took 2,15 s, known ones 0,53 s. */
class SearchWarmupTest {

    private final CanonicalProductSearchService search = mock(CanonicalProductSearchService.class);

    @Test
    void forgottenWordsAreLookedUpAgainOnceInTheBackground() {
        CatalogMemo memo = new CatalogMemo(true);
        SearchWarmup warmup = new SearchWarmup(search, memo, mock(JdbcClient.class), true, 10, 0);
        try {
            memo.rememberLarge(List.of(CatalogMemo.SEARCH_CANDIDATES, "mleko", ""), () -> List.of());
            memo.rememberLarge(List.of(CatalogMemo.SEARCH_CANDIDATES, "hleb", ""), () -> List.of());

            // An import forgets once per chain: the words are looked up once.
            memo.catalogChanged();
            warmup.lookUpAgain(List.of("mleko"));

            verify(search, timeout(2_000)).warm("mleko");
            verify(search, timeout(2_000)).warm("hleb");
            verify(search, times(1)).warm("mleko");
        } finally {
            warmup.destroy();
        }
    }

    @Test
    void nothingIsLookedUpWhenTheMemoryIsOff() {
        CatalogMemo memo = new CatalogMemo(false);
        SearchWarmup warmup = new SearchWarmup(search, memo, mock(JdbcClient.class), true, 0, 0);
        try {
            warmup.lookUpAgain(List.of("mleko"));
            warmup.afterStart();

            verify(search, after(200).never()).warm("mleko");
        } finally {
            warmup.destroy();
        }
    }
}
