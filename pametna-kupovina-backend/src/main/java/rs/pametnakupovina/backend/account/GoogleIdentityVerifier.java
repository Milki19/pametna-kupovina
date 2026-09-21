package rs.pametnakupovina.backend.account;

import tools.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Turns the token the phone got from Google into the one thing worth keeping:
 * `sub`, the number by which Google knows it is the same person again. The
 * name, the picture and the address are in that token too and none of them
 * are read.
 *
 * <p>Google checks the signature itself, at an address meant for exactly
 * this, which is why the app carries no library for reading tokens: a
 * hand-written one is where this kind of code goes wrong, and someone signs
 * in about once per phone. What is checked here is what Google's answer
 * cannot decide for us — that the token was issued for <em>this</em> app and
 * has not expired.
 */
@Component
public class GoogleIdentityVerifier {

    public static final String PROVIDER = "GOOGLE";

    private final RestClient restClient;
    private final String expectedAudience;
    private final String tokenInfoUrl;

    public GoogleIdentityVerifier(
            @Value("${account.google.client-id:}") String expectedAudience,
            @Value("${account.google.token-info-url:https://oauth2.googleapis.com/tokeninfo}")
            String tokenInfoUrl,
            @Value("${account.google.timeout-seconds:10}") long timeoutSeconds
    ) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build());
        requestFactory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));

        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "PametnaKupovina/1.0")
                .build();
        this.tokenInfoUrl = tokenInfoUrl;
        this.expectedAudience = expectedAudience == null
                ? ""
                : expectedAudience.strip();
    }

    public String subjectOf(String idToken) {
        if (expectedAudience.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Prijava nije podešena na serveru."
            );
        }

        if (idToken == null || idToken.isBlank()) {
            throw badToken("Token prijave je prazan.");
        }

        JsonNode claims;

        try {
            claims = restClient.get()
                    .uri(tokenInfoUrl, builder -> builder
                            .queryParam("id_token", idToken.strip())
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RuntimeException exception) {
            // Google odbija neispravan token sa 400; sve ostalo je isto tako
            // razlog da se ne pusti unutra.
            throw badToken("Google nije prihvatio token prijave.");
        }

        if (claims == null) {
            throw badToken("Google nije vratio podatke o tokenu.");
        }

        String issuer = claims.path("iss").asText("");

        if (!"accounts.google.com".equals(issuer)
                && !"https://accounts.google.com".equals(issuer)) {
            throw badToken("Token nije izdao Google.");
        }

        if (!expectedAudience.equals(claims.path("aud").asText(""))) {
            throw badToken("Token je izdat za drugu aplikaciju.");
        }

        long expiresAt = claims.path("exp").asLong(0);

        if (Instant.ofEpochSecond(expiresAt)
                .isBefore(Instant.now().minus(Duration.ofSeconds(30)))) {
            throw badToken("Token prijave je istekao.");
        }

        String subject = claims.path("sub").asText("").strip();

        if (subject.isEmpty()) {
            throw badToken("Token ne kaže ko je korisnik.");
        }

        return subject;
    }

    /** Ne otkriva se šta tačno nije valjalo; to pomaže samo onome ko proba. */
    private static ResponseStatusException badToken(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }
}
