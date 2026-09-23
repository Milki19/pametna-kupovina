package rs.pametnakupovina.backend.account;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many times each key may act per window. The count lives in memory: the
 * server is one process, and a restart forgiving someone their last minute is
 * the cheapest possible price for not writing a row per request.
 */
final class FixedWindowLimiter<K> {

    private static final int MOST_REMEMBERED_KEYS = 10_000;

    private final int limit;
    private final Duration window;
    private final Map<K, Window> windows = new ConcurrentHashMap<>();

    FixedWindowLimiter(int limit, Duration window) {
        if (limit < 1) {
            throw new IllegalArgumentException("Dozvoljeni broj mora biti bar 1.");
        }
        this.limit = limit;
        this.window = window;
    }

    boolean allow(K key) {
        Instant now = Instant.now();
        forgetOldWindows(now);

        Window counted = windows.compute(key, (ignored, existing) ->
                existing == null || existing.startedBefore(now, window)
                        ? new Window(now)
                        : existing);

        return counted.count() <= limit;
    }

    /**
     * A map that only grows would outlive the server. Old windows are dropped
     * once there are enough of them to be worth walking.
     */
    private void forgetOldWindows(Instant now) {
        if (windows.size() <= MOST_REMEMBERED_KEYS) {
            return;
        }

        windows.values().removeIf(counted -> counted.startedBefore(now, window));
    }

    private static final class Window {

        private final Instant startedAt;
        private final AtomicInteger uses = new AtomicInteger();

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean startedBefore(Instant now, Duration window) {
            return startedAt.plus(window).isBefore(now);
        }

        private int count() {
            return uses.incrementAndGet();
        }
    }
}
