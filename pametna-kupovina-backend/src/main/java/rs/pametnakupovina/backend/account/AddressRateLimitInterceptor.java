package rs.pametnakupovina.backend.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Counting per account stops one phone, not a script that makes up a new
 * phone for every request — each of those is a fresh account row and a fresh
 * search on a two-core server. So every request is also counted per network
 * address (Caddy's X-Forwarded-For, which it sets itself).
 *
 * <p>ponytail: one fixed window per address, generous because a mobile
 * carrier puts many shoppers behind one address; a stricter cap on brand-new
 * devices if abuse shows up.
 */
@Component
public class AddressRateLimitInterceptor implements HandlerInterceptor {

    static final int REQUESTS_PER_MINUTE = 600;

    private final RequestCounter counter;

    public AddressRateLimitInterceptor(RequestCounter counter) {
        this.counter = counter;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        if (counter.countAndGet("address:" + request.getRemoteAddr(), Duration.ofMinutes(1))
                > REQUESTS_PER_MINUTE) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Previše zahteva sa ove mreže. Sačekaj minut pa probaj ponovo."
            );
        }

        return true;
    }
}
