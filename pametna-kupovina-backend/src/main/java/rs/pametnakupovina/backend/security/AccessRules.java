package rs.pametnakupovina.backend.security;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * Who may call what, in one place. Every API path is listed; a path that is
 * not is refused, so a new endpoint stays closed until someone decides who
 * it is for. Pages outside /api (the web app, /admin's own HTML, the legal
 * pages) are public — the admin page reads its data through /api, which is
 * where the key is asked for.
 */
public final class AccessRules {

    public enum Access {
        /** Anyone; a proved phone is not needed and not looked up. */
        PUBLIC,
        /** Anyone; a phone that proves itself is recorded (e.g. product reports). */
        DEVICE_OPTIONAL,
        /** Only a phone with a live session (or, while allowed, an old app's number). */
        DEVICE,
        /** Only the owner, with the admin key. */
        ADMIN,
        /** Not listed: refused. */
        DENIED
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final List<Rule> RULES = List.of(
            // Sesija: otvaranje i obnova su baš način da se dobije token.
            new Rule(HttpMethod.POST, "/api/v1/sessions", Access.PUBLIC),
            new Rule(HttpMethod.POST, "/api/v1/sessions/refresh", Access.PUBLIC),
            new Rule(HttpMethod.DELETE, "/api/v1/sessions/current", Access.DEVICE),

            // Administracija: uvozi, kvalitet podataka, padovi, geokodiranje.
            new Rule(null, "/api/v1/imports/**", Access.ADMIN),
            new Rule(null, "/api/v1/stores/geocoding-review-queue", Access.ADMIN),
            new Rule(null, "/api/v1/stores/*/geocoding-results", Access.ADMIN),
            new Rule(null, "/api/v1/stores/*/geocoding-review", Access.ADMIN),

            // Ono što pripada nalogu.
            new Rule(null, "/api/v1/accounts/**", Access.DEVICE),
            new Rule(null, "/api/v1/shopping-lists/**", Access.DEVICE),
            new Rule(null, "/api/v1/shopping-lists", Access.DEVICE),
            new Rule(null, "/api/v1/receipts/**", Access.DEVICE),
            new Rule(null, "/api/v1/receipts", Access.DEVICE),
            new Rule(null, "/api/v1/loyalty-cards/**", Access.DEVICE),
            new Rule(null, "/api/v1/loyalty-cards", Access.DEVICE),

            // Javno: cene su javne, a prijava greške ne traži nalog.
            new Rule(HttpMethod.POST, "/api/v1/products/*/reports", Access.DEVICE_OPTIONAL),
            new Rule(HttpMethod.POST, "/api/v1/products/match-decisions", Access.PUBLIC),
            new Rule(HttpMethod.POST, "/api/v1/products/match-decisions/*/feedback", Access.PUBLIC),
            new Rule(HttpMethod.GET, "/api/v1/products/**", Access.PUBLIC),
            new Rule(HttpMethod.GET, "/api/v1/stores/nearby", Access.PUBLIC),
            new Rule(HttpMethod.GET, "/api/v1/retailers", Access.PUBLIC),
            new Rule(HttpMethod.GET, "/api/v1/retailer-locations/**", Access.PUBLIC),
            new Rule(HttpMethod.POST, "/api/v1/crash-reports", Access.PUBLIC)
    );

    private AccessRules() {
    }

    public static Access of(String method, String path) {
        if (!path.equals("/api") && !path.startsWith("/api/")) {
            return Access.PUBLIC;
        }

        if (HttpMethod.OPTIONS.matches(method)) {
            return Access.PUBLIC;
        }

        for (Rule rule : RULES) {
            if ((rule.method() == null || rule.method().matches(method))
                    && MATCHER.match(rule.pattern(), path)) {
                return rule.access();
            }
        }

        return Access.DENIED;
    }

    private record Rule(HttpMethod method, String pattern, Access access) {
    }
}
