package rs.pametnakupovina.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Checks the credential a request's path asks for — the admin key, or a
 * phone's session — and tells Spring Security who proved themselves. It
 * decides nothing: whether that is enough is up to the rules in
 * {@link SecurityConfiguration}. A proved phone is also attached to the
 * request as a {@link DeviceCaller} for the controllers.
 *
 * <p>Not a bean on purpose: as one, Spring Boot would also run it outside
 * the security chain, a second time.
 */
class CredentialsFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    public static final String LEGACY_TOKEN_HEADER = "X-Client-Token";

    private static final String BEARER = "Bearer ";

    private final DeviceSessionService sessionService;
    private final byte[] adminKey;

    CredentialsFilter(DeviceSessionService sessionService, String adminKey) {
        this.sessionService = sessionService;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        if (looksLikePathTrickery(request.getRequestURI())) {
            Refusals.write(response, HttpStatus.BAD_REQUEST, "Neispravna putanja.", null);
            return;
        }

        try {
            switch (AccessRules.of(request)) {
                case ADMIN -> {
                    if (adminKeyMatches(request.getHeader(ADMIN_KEY_HEADER))) {
                        authenticate(authenticated("admin", SecurityConfiguration.ADMIN_ROLE));
                    }
                }
                case DEVICE, DEVICE_OPTIONAL -> callerOf(request).ifPresent(caller -> {
                    caller.attachTo(request);
                    authenticate(authenticated(caller, SecurityConfiguration.DEVICE_ROLE));
                });
                case PUBLIC, DENIED -> {
                }
            }
        } catch (ResponseStatusException refused) {
            // Npr. stari broj telefona duži od dozvoljenog.
            Refusals.write(response, HttpStatus.valueOf(refused.getStatusCode().value()), refused.getReason(), null);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * A session token wins; an old app's number is tried only when there is
     * none, and only while such numbers are still accepted.
     */
    private Optional<DeviceCaller> callerOf(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return sessionService.callerForAccessToken(authorization.substring(BEARER.length()));
        }

        String legacyToken = request.getHeader(LEGACY_TOKEN_HEADER);

        if (legacyToken != null && !legacyToken.isBlank()) {
            return sessionService.callerForLegacyToken(legacyToken);
        }

        return Optional.empty();
    }

    private boolean adminKeyMatches(String supplied) {
        return adminKey.length > 0
                && supplied != null
                && MessageDigest.isEqual(adminKey, supplied.strip().getBytes(StandardCharsets.UTF_8));
    }

    private static Authentication authenticated(Object principal, String role) {
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_" + role));
    }

    private static void authenticate(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    /**
     * The rules are matched against the path the dispatcher routes, so any
     * way of spelling one path as another ("/a/../b", "%61", "//", ";x") is
     * refused outright rather than normalised in two places that could
     * disagree. No client of this API sends such a path: its path segments
     * are numbers and chain codes, and text travels in the query string.
     * Spring Security's firewall refuses most of these already; this also
     * catches the rest ("%61").
     */
    static boolean looksLikePathTrickery(String uri) {
        return uri != null
                && (uri.contains("/.")
                || uri.contains("//")
                || uri.contains(";")
                || uri.contains("\\")
                || uri.contains("%"));
    }
}
