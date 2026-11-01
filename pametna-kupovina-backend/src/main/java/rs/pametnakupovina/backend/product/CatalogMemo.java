package rs.pametnakupovina.backend.product;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import rs.pametnakupovina.backend.SingleFlight;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * What a word finds in the catalogue: its kind, the products whose names say
 * it, the families a search lists for it. That changes only when a chain's
 * products do, so it is worked out once and kept until the next import or
 * review of the catalogue, at most an hour (the usable prices count today's).
 * Prices and sales are never kept here: they are read live, so a sale
 * imported at 10:00 shows at 10:00. What a search finds may say whether a
 * product sells today and in how many chains: that is the presence table,
 * which only the catalogue refresh after an import writes.
 *
 * <p>A word's first check costs the queries; the same word for anyone after
 * that costs nothing (brzina na 2 jezgra, 31.10.2026).
 */
@Component
public class CatalogMemo {

    static final Duration MAX_AGE = Duration.ofHours(1);

    /** Enough for every word of a few thousand lists; past it, start over. */
    static final int MAX_ENTRIES = 50_000;

    private final boolean enabled;
    private final Clock clock;
    /** A search's families: a few hundred rows each, so far fewer of them. */
    static final int MAX_LARGE_ENTRIES = 500;

    private final Map<List<?>, Optional<Object>> entries = new ConcurrentHashMap<>();
    private final Map<List<?>, Optional<Object>> largeEntries = new ConcurrentHashMap<>();
    private final SingleFlight<List<?>> inFlight = new SingleFlight<>();
    // Raised on every change, so a lookup that began before it is not kept.
    private final AtomicLong generation = new AtomicLong();
    private volatile Instant startedAt;

    @Autowired
    public CatalogMemo(@Value("${catalog-memo.enabled:true}") boolean enabled) {
        this(enabled, Clock.systemUTC());
    }

    CatalogMemo(boolean enabled, Clock clock) {
        this.enabled = enabled;
        this.clock = clock;
        this.startedAt = clock.instant();
    }

    /**
     * The answer kept for this lookup, or the lookup's answer, kept. The
     * same lookup asked for at once by several people is done once.
     *
     * @param key what was asked: a name for the lookup, then its arguments
     */
    public <T> T remember(List<?> key, Supplier<T> lookup) {
        return remember(entries, MAX_ENTRIES, key, lookup);
    }

    /**
     * {@link #remember} for answers of hundreds of rows, such as the families
     * a search finds: only the last {@link #MAX_LARGE_ENTRIES} questions are
     * kept, so a day of searches cannot fill the memory.
     */
    public <T> T rememberLarge(List<?> key, Supplier<T> lookup) {
        return remember(largeEntries, MAX_LARGE_ENTRIES, key, lookup);
    }

    @SuppressWarnings("unchecked")
    private <T> T remember(
            Map<List<?>, Optional<Object>> kept,
            int maxEntries,
            List<?> key,
            Supplier<T> lookup
    ) {
        if (!enabled) {
            return lookup.get();
        }
        if (startedAt.plus(MAX_AGE).isBefore(clock.instant())) {
            forget();
        }
        Optional<Object> found = kept.get(key);
        if (found != null) {
            return (T) found.orElse(null);
        }
        long before = generation.get();
        // Only a lookup begun since the last change is waited for.
        return inFlight.run(List.of(before, key), () -> {
            T answer = lookup.get();
            if (generation.get() == before) {
                if (kept.size() >= maxEntries) {
                    kept.clear();
                }
                kept.put(key, Optional.ofNullable(answer));
            }
            return answer;
        });
    }

    /** The catalogue changed; inside a transaction, once it commits. */
    public void catalogChanged() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Also now: a lookup until the commit must not keep what is
            // about to change.
            generation.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    forget();
                }
            });
        } else {
            forget();
        }
    }

    private void forget() {
        generation.incrementAndGet();
        entries.clear();
        largeEntries.clear();
        startedAt = clock.instant();
    }

    int size() {
        return entries.size() + largeEntries.size();
    }
}
