package rs.pametnakupovina.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.header.writers.CacheControlHeadersWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Who may call what is decided here, by Spring Security, from the list in
 * {@link AccessRules}: a phone with a live session, the owner with the admin
 * key, or anyone. Nothing is remembered between requests — no cookies, no
 * server-side HTTP session — so there is nothing for CSRF to ride on.
 *
 * <p>The admin key has no "off" switch: without a configured key the admin
 * API answers nobody, in every profile, the developer's laptop included.
 */
@Configuration
public class SecurityConfiguration {

    static final String DEVICE_ROLE = "DEVICE";
    static final String ADMIN_ROLE = "ADMIN";

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);
    private static final int SHORTEST_SENSIBLE_ADMIN_KEY = 32;

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            DeviceSessionService sessionService,
            @Value("${admin.api-key:}") String configuredAdminKey
    ) throws Exception {
        String adminKey = configuredAdminKey == null ? "" : configuredAdminKey.strip();

        if (adminKey.isEmpty()) {
            log.info("admin.api-key nije postavljen: administratorski API je zatvoren.");
        } else if (adminKey.length() < SHORTEST_SENSIBLE_ADMIN_KEY) {
            log.warn("admin.api-key je kraći od {} znakova; zameni ga dužim.", SHORTEST_SENSIBLE_ADMIN_KEY);
        }

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .headers(headers -> headers
                        // Stranice i skripte web aplikacije keširaju se kao i do sada
                        // (?v= u index.html); odgovori API-ja nose lične podatke.
                        .cacheControl(cache -> cache.disable())
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                                new CacheControlHeadersWriter()))
                        // Skripta sa ?v= se ne menja dok se broj ne podigne, pa
                        // je pregledač ne traži ponovo; stranica i sw.js uvek.
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                SecurityConfiguration::isVersionedWebFile,
                                new StaticHeadersWriter("Cache-Control", "public, max-age=31536000, immutable")))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                SecurityConfiguration::isWebEntryPoint,
                                new StaticHeadersWriter("Cache-Control", "no-cache"))))
                .addFilterBefore(new CredentialsFilter(sessionService, adminKey), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(AccessRules::applyTo)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                Refusals.unauthenticated(request, response))
                        .accessDeniedHandler((request, response, denied) ->
                                Refusals.forbidden(response)))
                .build();
    }

    private static boolean isVersionedWebFile(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/app/")
                && !isWebEntryPoint(request)
                && request.getParameter("v") != null;
    }

    private static boolean isWebEntryPoint(jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/app") || path.equals("/app/")
                || path.equals("/app/index.html") || path.equals("/app/sw.js");
    }

    /** A path the firewall refuses is the caller's mistake: 400, not 500. */
    @Bean
    RequestRejectedHandler requestRejectedHandler() {
        return new HttpStatusRequestRejectedHandler();
    }
}
