package rs.pametnakupovina.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.time.Duration;

/**
 * Nothing stopped one phone from filling the database. Reading stays free —
 * prices are public — but writing is counted per account, so a shopper who
 * pastes a long list never notices while a script does.
 */
@Component
public class WriteRateLimitInterceptor implements HandlerInterceptor {

    private final RequestCounter counter;
    private final int writesPerMinute;

    public WriteRateLimitInterceptor(
            RequestCounter counter,
            @Value("${account.writes-per-minute:120}") int writesPerMinute
    ) {
        if (writesPerMinute < 1) {
            throw new IllegalArgumentException("Dozvoljeni broj mora biti bar 1.");
        }
        this.counter = counter;
        this.writesPerMinute = writesPerMinute;
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

        DeviceCaller caller = DeviceCaller.of(request);

        if (caller == null) {
            // Whoever the endpoint is for will say so itself; this is not the
            // place to invent an authentication rule.
            return true;
        }

        if (counter.countAndGet("account:" + caller.accountId(), Duration.ofMinutes(1))
                > writesPerMinute) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Previše izmena u kratkom roku. Sačekaj minut pa probaj ponovo."
            );
        }

        return true;
    }
}
