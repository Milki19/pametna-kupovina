package rs.pametnakupovina.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nothing stopped one phone from filling the database. Reading stays free —
 * prices are public — but writing is counted per account, so a shopper who
 * pastes a long list never notices while a script does.
 *
 * <p>The count lives in memory: the server is one process, and a restart
 * forgiving someone their last minute is the cheapest possible price for not
 * writing a row per request.
 */
@Component
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    private static final int MOST_REMEMBERED_ACCOUNTS = 10_000;

    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final AccountRepository accountRepository;
    private final int writesPerWindow;
    private final Duration window;
    private final Map<Long, Window> windows = new ConcurrentHashMap<>();

    public WriteRateLimitInterceptor(
            ShoppingListClientTokenPolicy clientTokenPolicy,
            AccountRepository accountRepository,
            @Value("${account.writes-per-minute:120}") int writesPerMinute
    ) {
        this.clientTokenPolicy = clientTokenPolicy;
        this.accountRepository = accountRepository;
        this.writesPerWindow = writesPerMinute;
        this.window = Duration.ofMinutes(1);

        if (writesPerMinute < 1) {
            throw new IllegalArgumentException(
                    "Broj dozvoljenih upisa mora biti bar 1."
            );
        }
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if ("GET".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod())) {
            return true;
        }

        String clientToken = request.getHeader("X-Client-Token");

        if (clientToken == null || clientToken.isBlank()) {
            // Whoever the endpoint is for will say so itself; this is not the
            // place to invent an authentication rule.
            return true;
        }

        long accountId = accountRepository.forDevice(
                clientTokenPolicy.validateAndHash(clientToken)
        );

        if (!allow(accountId)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Previše izmena u kratkom roku. Sačekaj minut pa probaj ponovo."
            );
        }

        return true;
    }

    private boolean allow(long accountId) {
        Instant now = Instant.now();
        forgetOldWindows(now);

        Window counted = windows.compute(accountId, (key, existing) ->
                existing == null || existing.startedBefore(now, window)
                        ? new Window(now)
                        : existing);

        return counted.count() <= writesPerWindow;
    }

    /**
     * A map that only grows would outlive the server. Old windows are dropped
     * once there are enough of them to be worth walking.
     */
    private void forgetOldWindows(Instant now) {
        if (windows.size() <= MOST_REMEMBERED_ACCOUNTS) {
            return;
        }

        windows.values().removeIf(counted ->
                counted.startedBefore(now, window));
    }

    private static final class Window {

        private final Instant startedAt;
        private final AtomicInteger writes = new AtomicInteger();

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }

        private boolean startedBefore(Instant now, Duration window) {
            return startedAt.plus(window).isBefore(now);
        }

        private int count() {
            return writes.incrementAndGet();
        }
    }
}
