package rs.pametnakupovina.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * Who may call what, in one place. Every API path is listed; a path that is
 * not is refused, so a new endpoint stays closed until someone decides who
 * it is for. Pages outside /api (the web app, /admin's own HTML, the legal
 * pages) are public — the admin page reads its data through /api, which is
 * where the key is asked for.
 *
 * <p>Spring Security enforces the list ({@link SecurityConfiguration}); the
 * same patterns tell {@link CredentialsFilter} which credential a request
 * needs checked, so a public request never costs a session lookup.
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

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

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
            // Adresa polazne tačke ide spolja (OpenStreetMap), pa samo uz sesiju.
            new Rule(HttpMethod.GET, "/api/v1/places", Access.DEVICE),

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

    public static Access of(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        return of(request.getMethod(), path.isEmpty() ? "/" : path);
    }

    public static Access of(String method, String path) {
        if (!isApi(path)) {
            return Access.PUBLIC;
        }

        if (HttpMethod.OPTIONS.matches(method)) {
            return Access.PUBLIC;
        }

        PathContainer container = PathContainer.parsePath(path);

        for (Rule rule : RULES) {
            if ((rule.method() == null || rule.method().matches(method))
                    && rule.pattern().matches(container)) {
                return rule.access();
            }
        }

        return Access.DENIED;
    }

    /**
     * Hands the list to Spring Security in the same order, so the first rule
     * that matches decides there too; everything else under /api is denied.
     */
    static void applyTo(
            AuthorizeHttpRequestsConfigurer<?>.AuthorizationManagerRequestMatcherRegistry registry
    ) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();

        registry.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll();

        for (Rule rule : RULES) {
            var matched = registry.requestMatchers(paths.matcher(rule.method(), rule.pattern().getPatternString()));

            switch (rule.access()) {
                case PUBLIC, DEVICE_OPTIONAL -> matched.permitAll();
                case DEVICE -> matched.hasRole(SecurityConfiguration.DEVICE_ROLE);
                case ADMIN -> matched.hasRole(SecurityConfiguration.ADMIN_ROLE);
                case DENIED -> matched.denyAll();
            }
        }

        registry.requestMatchers(paths.matcher("/api")).denyAll();
        registry.requestMatchers(paths.matcher("/api/**")).denyAll();
        registry.anyRequest().permitAll();
    }

    private static boolean isApi(String path) {
        return path.equals("/api") || path.startsWith("/api/");
    }

    private record Rule(HttpMethod method, PathPattern pattern, Access access) {

        Rule(HttpMethod method, String pattern, Access access) {
            this(method, PARSER.parse(pattern), access);
        }
    }
}
