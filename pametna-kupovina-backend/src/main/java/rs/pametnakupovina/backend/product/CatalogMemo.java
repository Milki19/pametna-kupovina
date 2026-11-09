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
 *
 * <p>A plan's offers and a page of sales are the exception: they carry
 * prices, and working them out was 90% of the database's time under load
 * (k6, 3.11.2026). The same list in the same shops, or the same sales page
 * for the same place, is worked out once and kept {@link #OFFER_MAX_AGE}: an
 * import or a new read of the sales clears them at once, a "not in this
 * shop" report too, and anything else (a shop's format, a kind's settings)
 * shows within minutes.
 *
 * <p>Whole answers of a search and of a sales page are kept for everyone
 * ({@link #rememberShared}), {@link #SHARED_MAX_AGE} at most: the same words
 * or the same sales from the same neighbourhood (rounded to about a
 * kilometre) cost the first person, not each one (šesti test, 6.11.2026:
 * every phone paid its own first search). A place in a key is never kept
 * longer than that: what is older is dropped every minute, not only when
 * asked for again.
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

    static final Duration OFFER_MAX_AGE = Duration.ofMinutes(10);
    /**
     * A plan's offers (a few hundred rows for a list and group of shops), one
     * item's offers in a group of shops (a row per shop), and sale pages.
     */
    static final int MAX_OFFER_ENTRIES = 4_000;
    private final Map<List<?>, KeptOffers> offerEntries = new ConcurrentHashMap<>();

    private record KeptOffers(Instant keptAt, Object offers) {
    }

    /** As the privacy page says: a rounded place is kept five minutes at most. */
    static final Duration SHARED_MAX_AGE = Duration.ofMinutes(
            rs.pametnakupovina.backend.privacy.PreciseLocationPolicy.ROUGH_RETENTION_MINUTES);
    /** A search page is 5 to 20 products; a few thousand of them are a few MB. */
    static final int MAX_SHARED_ENTRIES = 3_000;
    private final Map<List<?>, KeptOffers> sharedEntries = new ConcurrentHashMap<>();
    private final SingleFlight<List<?>> inFlight = new SingleFlight<>();
    /** The first part of a search's key, as {@link CanonicalProductSearchService} keeps it. */
    static final String SEARCH_CANDIDATES = "searchCandidates";
    /** The first part of a whole search answer's key. */
    static final String SEARCH_ANSWER = "searchAnswer";
    private final List<java.util.function.Consumer<List<String>>> forgetListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
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

    /**
     * A plan's offers or a sales page, or the lookup's answer, kept
     * {@link #OFFER_MAX_AGE}. The key must say everything the answer depends
     * on but the prices: the list's items, the shops, the day, the filter.
     */
    @SuppressWarnings("unchecked")
    public <T> T rememberOffers(List<?> key, Supplier<T> lookup) {
        if (!enabled) {
            return lookup.get();
        }
        Instant now = clock.instant();
        KeptOffers found = offerEntries.get(key);
        if (found != null && !found.keptAt().plus(OFFER_MAX_AGE).isBefore(now)) {
            return (T) found.offers();
        }
        long before = generation.get();
        return inFlight.run(List.of(before, "offers", key), () -> {
            T answer = lookup.get();
            if (answer != null && generation.get() == before) {
                if (offerEntries.size() >= MAX_OFFER_ENTRIES) {
                    offerEntries.clear();
                }
                offerEntries.put(key, new KeptOffers(clock.instant(), answer));
            }
            return answer;
        });
    }

    /**
     * The whole answer to a search or a sales page, the same for everyone who
     * asks the same thing from the same rounded place, or the lookup's answer,
     * kept {@link #SHARED_MAX_AGE}. Several people asking at once wait for one
     * lookup. The key must say everything the answer depends on, the place
     * only {@linkplain rs.pametnakupovina.backend.privacy.PreciseLocation#roughly()
     * rounded}. Any change of the catalogue, the prices or the sales drops them.
     */
    @SuppressWarnings("unchecked")
    public <T> T rememberShared(List<?> key, Supplier<T> lookup) {
        if (!enabled) {
            return lookup.get();
        }
        KeptOffers found = sharedEntries.get(key);
        if (found != null && fresh(found, clock.instant())) {
            return (T) found.offers();
        }
        long before = generation.get();
        return inFlight.run(List.of(before, "shared", key), () -> {
            T answer = lookup.get();
            if (answer != null && generation.get() == before) {
                if (sharedEntries.size() >= MAX_SHARED_ENTRIES) {
                    forgetExpiredShared();
                    if (sharedEntries.size() >= MAX_SHARED_ENTRIES) {
                        sharedEntries.clear();
                    }
                }
                sharedEntries.put(key, new KeptOffers(clock.instant(), answer));
            }
            return answer;
        });
    }

    private static boolean fresh(KeptOffers kept, Instant now) {
        return kept.keptAt().plus(SHARED_MAX_AGE).isAfter(now);
    }

    /**
     * Drops every shared answer older than {@link #SHARED_MAX_AGE}, so a
     * rounded place in a key is gone within a minute of its five, even if
     * nobody asks the same again.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void forgetExpiredShared() {
        Instant now = clock.instant();
        sharedEntries.values().removeIf(kept -> !fresh(kept, now));
    }

    /** How many shared answers are kept now. */
    int sharedSize() {
        return sharedEntries.size();
    }

    /**
     * What {@link #rememberOffers} keeps, read without working it out: a plan
     * asks for many items at once and works out only the ones not kept.
     * Null when nothing fresh is kept.
     */
    @SuppressWarnings("unchecked")
    public <T> T keptOffers(List<?> key) {
        if (!enabled) {
            return null;
        }
        KeptOffers found = offerEntries.get(key);
        if (found != null && !found.keptAt().plus(OFFER_MAX_AGE).isBefore(clock.instant())) {
            return (T) found.offers();
        }
        return null;
    }

    /** Read before the lookup, for {@link #keepOffers}. */
    public long offersGeneration() {
        return generation.get();
    }

    /**
     * Keeps an answer worked out outside {@link #rememberOffers}, unless
     * something it read changed since {@code generationBefore}.
     */
    public void keepOffers(List<?> key, Object offers, long generationBefore) {
        if (!enabled || offers == null || generation.get() != generationBefore) {
            return;
        }
        if (offerEntries.size() >= MAX_OFFER_ENTRIES) {
            offerEntries.clear();
        }
        offerEntries.put(key, new KeptOffers(clock.instant(), offers));
    }

    /** Something only plans and sales read changed; once it commits. */
    public void offersChanged() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            generation.incrementAndGet();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    forgetOffers();
                }
            });
        } else {
            forgetOffers();
        }
    }

    /**
     * The sales list was read again: its pages and the searches go (a search
     * can ask only for what is on sale), the plans stay.
     */
    public void salesChanged() {
        generation.incrementAndGet();
        offerEntries.keySet().removeIf(key -> !key.isEmpty() && SALE_PAGE.equals(key.getFirst()));
        sharedEntries.clear();
    }

    /** The first part of a sales page's key. */
    public static final String SALE_PAGE = "salePage";

    private void forgetOffers() {
        generation.incrementAndGet();
        offerEntries.clear();
        sharedEntries.clear();
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
        List<String> searched = largeEntries.keySet().stream()
                .filter(key -> key.size() == 3 && SEARCH_CANDIDATES.equals(key.get(0))
                        && "".equals(key.get(2)))
                .map(key -> String.valueOf(key.get(1)))
                .toList();
        entries.clear();
        largeEntries.clear();
        offerEntries.clear();
        sharedEntries.clear();
        startedAt = clock.instant();
        if (enabled && !searched.isEmpty()) {
            forgetListeners.forEach(listener -> listener.accept(searched));
        }
    }

    /**
     * Told the words searched for until now, each time everything is
     * forgotten (an import, a review, the hour): so they can be looked up
     * again before someone asks.
     */
    public void onForget(java.util.function.Consumer<List<String>> listener) {
        forgetListeners.add(listener);
    }

    public boolean enabled() {
        return enabled;
    }

    int size() {
        return entries.size() + largeEntries.size() + offerEntries.size() + sharedEntries.size();
    }
}
