package rs.pametnakupovina.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Every API request passes here before any controller runs: the rule for its
 * path ({@link AccessRules}) says who may make it, and the request either
 * proves that or is turned away with 401/403. A proved phone is attached to
 * the request as a {@link DeviceCaller}.
 *
 * <p>The admin key has no "off" switch: without a configured key the admin
 * API answers nobody, in every profile, the developer's laptop included.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAccessFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";
    public static final String LEGACY_TOKEN_HEADER = "X-Client-Token";

    private static final Logger log = LoggerFactory.getLogger(ApiAccessFilter.class);
    private static final String BEARER = "Bearer ";
    private static final int SHORTEST_SENSIBLE_ADMIN_KEY = 32;

    private final DeviceSessionService sessionService;
    private final byte[] adminKey;

    public ApiAccessFilter(
            DeviceSessionService sessionService,
            @Value("${admin.api-key:}") String adminKey
    ) {
        this.sessionService = sessionService;
        String key = adminKey == null ? "" : adminKey.strip();
        this.adminKey = key.getBytes(StandardCharsets.UTF_8);

        if (key.isEmpty()) {
            log.info("admin.api-key nije postavljen: administratorski API je zatvoren.");
        } else if (key.length() < SHORTEST_SENSIBLE_ADMIN_KEY) {
            log.warn("admin.api-key je kraći od {} znakova; zameni ga dužim.", SHORTEST_SENSIBLE_ADMIN_KEY);
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        if (looksLikePathTrickery(request.getRequestURI())) {
            refuse(response, HttpStatus.BAD_REQUEST, "Neispravna putanja.", null);
            return;
        }

        AccessRules.Access access = AccessRules.of(request.getMethod(), pathOf(request));

        try {
            switch (access) {
                case PUBLIC -> {
                }
                case DENIED -> {
                    refuse(response, HttpStatus.FORBIDDEN, "Pristup nije dozvoljen.", null);
                    return;
                }
                case ADMIN -> {
                    if (!adminKeyMatches(request.getHeader(ADMIN_KEY_HEADER))) {
                        refuse(response, HttpStatus.UNAUTHORIZED,
                                "Nedostaje ispravan administratorski API ključ.", null);
                        return;
                    }
                }
                case DEVICE_OPTIONAL -> callerOf(request).ifPresent(caller -> caller.attachTo(request));
                case DEVICE -> {
                    Optional<DeviceCaller> caller = callerOf(request);

                    if (caller.isEmpty()) {
                        refuse(response, HttpStatus.UNAUTHORIZED,
                                request.getHeader(HttpHeaders.AUTHORIZATION) == null
                                        && request.getHeader(LEGACY_TOKEN_HEADER) != null
                                        ? "Ažuriraj aplikaciju da bi nastavio."
                                        : "Sesija je istekla. Otvori aplikaciju ponovo.",
                                DeviceSessionService.INVALID_TOKEN_CHALLENGE);
                        return;
                    }

                    caller.get().attachTo(request);
                }
            }
        } catch (ResponseStatusException refused) {
            // Npr. stari broj telefona duži od dozvoljenog.
            refuse(response, HttpStatus.valueOf(refused.getStatusCode().value()), refused.getReason(), null);
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

    /**
     * The rules are matched against the path the dispatcher routes, so any
     * way of spelling one path as another ("/a/../b", "%61", "//", ";x") is
     * refused outright rather than normalised in two places that could
     * disagree. No client of this API sends such a path: its path segments
     * are numbers and chain codes, and text travels in the query string.
     */
    static boolean looksLikePathTrickery(String rawUri) {
        if (rawUri == null) {
            return false;
        }

        String uri = rawUri;
        return uri.contains("/.")
                || uri.contains("//")
                || uri.contains(";")
                || uri.contains("\\")
                || uri.contains("%");
    }

    private static String pathOf(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();

        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }

        return path.isEmpty() ? "/" : path;
    }

    /** Same shape as ClientErrorResponses, so the app shows the message. */
    private static void refuse(
            HttpServletResponse response,
            HttpStatus status,
            String message,
            String challenge
    ) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        if (challenge != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }

        response.getWriter().write("{\"status\":" + status.value()
                + ",\"message\":\"" + jsonEscape(message) + "\"}");
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder escaped = new StringBuilder(text.length());

        for (char character : text.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }

        return escaped.toString();
    }
}
