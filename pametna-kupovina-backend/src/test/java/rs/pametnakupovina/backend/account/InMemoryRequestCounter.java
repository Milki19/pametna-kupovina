package rs.pametnakupovina.backend.account;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** The database counter without a database; one window that never ends. */
final class InMemoryRequestCounter implements RequestCounter {

    private final Map<String, Integer> uses = new HashMap<>();

    @Override
    public int countAndGet(String key, Duration window) {
        return uses.merge(key, 1, Integer::sum);
    }
}
