package rs.pametnakupovina.backend.account;

import java.time.Duration;

/** How many times a key has acted in the current window, this one included. */
public interface RequestCounter {

    int countAndGet(String key, Duration window);
}
