package rs.pametnakupovina.backend.account;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Google says whether it signed the token. What it cannot say is whether the
 * token was meant for us: the same Google account signing into someone else's
 * app produces a perfectly valid token, and letting one in would hand that
 * app's users our shoppers' lists.
 */
class GoogleIdentityVerifierTest {

    private static final String OURS = "nas-klijent.apps.googleusercontent.com";

    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void aTokenIssuedForThisAppGivesUpItsSubject() throws IOException {
        answer.set(claims(OURS, "accounts.google.com", inAnHour()));

        assertThat(verifier(OURS).subjectOf("token")).isEqualTo("1234567890");
    }

    @Test
    void aTokenIssuedForAnotherAppIsRefused() throws IOException {
        answer.set(claims(
                "tudja-aplikacija.apps.googleusercontent.com",
                "accounts.google.com",
                inAnHour()
        ));

        assertThatThrownBy(() -> verifier(OURS).subjectOf("token"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("drugu aplikaciju");
    }

    @Test
    void anExpiredTokenIsRefused() throws IOException {
        answer.set(claims(
                OURS,
                "accounts.google.com",
                Instant.now().minus(Duration.ofHours(1)).getEpochSecond()
        ));

        assertThatThrownBy(() -> verifier(OURS).subjectOf("token"))
                .hasMessageContaining("istekao");
    }

    @Test
    void aTokenFromSomewhereElseIsRefused() throws IOException {
        answer.set(claims(OURS, "zlonamerni.example", inAnHour()));

        assertThatThrownBy(() -> verifier(OURS).subjectOf("token"))
                .hasMessageContaining("nije izdao Google");
    }

    @Test
    void aTokenGoogleItselfRejectsIsRefused() throws IOException {
        status.set(400);
        answer.set("{\"error\":\"invalid_token\"}");

        assertThatThrownBy(() -> verifier(OURS).subjectOf("token"))
                .hasMessageContaining("Google nije prihvatio");
    }

    /** Dok klijent nije podešen, prijava ne sme da se pravi da radi. */
    @Test
    void withoutAConfiguredClientSignInSaysSoInsteadOfLettingAnyoneIn()
            throws IOException {
        answer.set(claims(OURS, "accounts.google.com", inAnHour()));

        assertThatThrownBy(() -> verifier("").subjectOf("token"))
                .hasMessageContaining("503")
                .hasMessageContaining("nije podešena");
    }

    @Test
    void anEmptyTokenNeverReachesGoogle() throws IOException {
        answer.set("{}");

        assertThatThrownBy(() -> verifier(OURS).subjectOf("  "))
                .hasMessageContaining("prazan");
    }

    private static long inAnHour() {
        return Instant.now().plus(Duration.ofHours(1)).getEpochSecond();
    }

    private static String claims(String audience, String issuer, long expiry) {
        return "{\"iss\":\"" + issuer + "\",\"aud\":\"" + audience
                + "\",\"sub\":\"1234567890\",\"exp\":\"" + expiry + "\"}";
    }

    private GoogleIdentityVerifier verifier(String clientId) throws IOException {
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/tokeninfo", exchange -> {
                byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders()
                        .add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), body.length);

                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
        }

        return new GoogleIdentityVerifier(
                clientId,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/tokeninfo",
                5
        );
    }
}
