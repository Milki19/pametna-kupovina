package rs.pametnakupovina.backend.receipt;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kod sa računa nosi `%2F` i `%2B`. Adresa je jednom bila predata kao šablon,
 * pa su se ti znaci prekodirali, Poreska uprava je vratila 400, a greška se
 * gutala — račun je ostajao bez stavki i niko nije znao zašto.
 */
class FiscalReceiptClientTest {

    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void theAddressFromTheReceiptArrivesUntouched() throws IOException {
        String code = "A1ZCTUhYOVNYVzZVQlBaTzCyKwEA%2FmgAAMDh5AAAAAAAAAABhXch%2Bkg%3D";
        start("""
                {"isValid":true,
                 "journal":"============ ФИСКАЛНИ РАЧУН ============",
                 "invoiceRequest":{"taxId":"112392483",
                                   "businessName":"RESTAURANT PLEASURE PARK ČAIR",
                                   "locationName":"1207756-PLEASURE PARK ČAIR"}}
                """);

        var fetched = client().fetch(url() + "?vl=" + code);

        assertThat(receivedQuery.get()).isEqualTo("vl=" + code);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().journal()).contains("ФИСКАЛНИ РАЧУН");
        // Kupcu znači objekat, ne firma, i bez broja ispred njega.
        assertThat(fetched.get().shopName()).isEqualTo("PLEASURE PARK ČAIR");
        assertThat(fetched.get().taxIdentificationNumber()).isEqualTo("112392483");
    }

    @Test
    void aReceiptTheTaxOfficeCallsInvalidIsNotRead() throws IOException {
        start("{\"isValid\":false,\"journal\":\"nešto\"}");

        assertThat(client().fetch(url() + "?vl=x")).isEmpty();
    }

    @Test
    void aTaxOfficeThatDoesNotAnswerLeavesTheReceiptWithoutLines()
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();

        assertThat(client().fetch(url() + "?vl=x")).isEmpty();
    }

    /** Bez naziva objekta ostaje firma; prazno je bolje nego izmišljeno. */
    @Test
    void withoutALocationTheCompanyNameWillDo() throws IOException {
        start("""
                {"isValid":true,"journal":"račun",
                 "invoiceRequest":{"businessName":"PRODAVNICA DOO","locationName":""}}
                """);

        assertThat(client().fetch(url() + "?vl=x"))
                .get()
                .satisfies(fetched -> {
                    assertThat(fetched.shopName()).isEqualTo("PRODAVNICA DOO");
                    assertThat(fetched.taxIdentificationNumber()).isNull();
                });
    }

    private void start(String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v/", exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v/";
    }

    private static FiscalReceiptClient client() {
        return new FiscalReceiptClient(5);
    }
}
