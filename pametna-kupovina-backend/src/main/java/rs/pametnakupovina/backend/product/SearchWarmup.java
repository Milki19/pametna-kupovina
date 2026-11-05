package rs.pametnakupovina.backend.product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Looks up again, in the background, the words people search for, each time
 * {@link CatalogMemo} forgets them (an import, a review, the hour) and after
 * a restart. A word's first search costs the catalogue queries: twenty
 * different searches at once took 2,15 s after a restart and 0,53 s once
 * known (proba 5.11.2026, two cores). Now the first one is mostly a known one.
 *
 * <p>One word at a time on one thread, a little after the change, so the
 * lookups never take both cores from the people using the app.
 */
@Component
public class SearchWarmup implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SearchWarmup.class);

    /** Fewer than {@link CatalogMemo#MAX_LARGE_ENTRIES}: room stays for new words. */
    static final int MAX_WORDS = 300;

    private final CanonicalProductSearchService searchService;
    private final CatalogMemo memo;
    private final JdbcClient jdbcClient;
    private final boolean enabled;
    private final Duration delay;
    private final Duration pause;
    private final ScheduledExecutorService executor;

    private final Set<String> waiting = new LinkedHashSet<>();
    private ScheduledFuture<?> scheduled;

    public SearchWarmup(
            CanonicalProductSearchService searchService,
            CatalogMemo memo,
            JdbcClient jdbcClient,
            @Value("${search-warmup.enabled:true}") boolean enabled,
            @Value("${search-warmup.delay-ms:30000}") long delayMs,
            @Value("${search-warmup.pause-ms:50}") long pauseMs
    ) {
        this.searchService = searchService;
        this.memo = memo;
        this.jdbcClient = jdbcClient;
        this.enabled = enabled && memo.enabled();
        this.delay = Duration.ofMillis(delayMs);
        this.pause = Duration.ofMillis(pauseMs);
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "search-warmup");
            thread.setDaemon(true);
            return thread;
        });
        if (this.enabled) {
            memo.onForget(this::lookUpAgain);
        }
    }

    /**
     * After a restart nothing is known: the words most lists hold are the
     * ones people search for most.
     */
    @EventListener(ApplicationReadyEvent.class)
    void afterStart() {
        if (!enabled) {
            return;
        }
        executor.schedule(() -> {
            try {
                lookUpAgain(wordsMostListsHold());
            } catch (RuntimeException failure) {
                log.warn("Reči sa spiskova nisu pročitane za zagrevanje pretrage: {}", failure.getMessage());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Words the memory forgot: looked up again a little later, once. */
    synchronized void lookUpAgain(Collection<String> words) {
        if (!enabled || words.isEmpty()) {
            return;
        }
        for (String word : words) {
            if (waiting.size() >= MAX_WORDS) {
                break;
            }
            waiting.add(word);
        }
        // An import forgets once per chain: start after the last of them.
        if (scheduled != null) {
            scheduled.cancel(false);
        }
        scheduled = executor.schedule(this::run, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void run() {
        List<String> words;
        synchronized (this) {
            words = new ArrayList<>(waiting);
            waiting.clear();
            scheduled = null;
        }
        long started = System.nanoTime();
        int looked = 0;
        for (String word : words) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            try {
                searchService.warm(word);
                looked++;
            } catch (RuntimeException failure) {
                log.debug("Pretraga za \"{}\" nije zagrejana: {}", word, failure.getMessage());
            }
            try {
                Thread.sleep(pause.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("Pretraga zagrejana: {} reči za {} ms", looked,
                Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    /** At least two lists, so no one's own word is looked up for them alone. */
    private List<String> wordsMostListsHold() {
        return jdbcClient.sql("""
                        SELECT LOWER(BTRIM(item.name)) AS word
                        FROM app.shopping_list_item AS item
                        GROUP BY LOWER(BTRIM(item.name))
                        HAVING COUNT(DISTINCT item.shopping_list_id) >= 2
                        ORDER BY COUNT(DISTINCT item.shopping_list_id) DESC,
                                 LOWER(BTRIM(item.name))
                        LIMIT :limit
                        """)
                .param("limit", MAX_WORDS)
                .query(String.class)
                .list();
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
