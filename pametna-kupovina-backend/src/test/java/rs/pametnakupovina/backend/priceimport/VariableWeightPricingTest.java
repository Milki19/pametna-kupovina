package rs.pametnakupovina.backend.priceimport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goods of variable weight are quoted per kilogram with both price columns
 * repeating that figure, so reading them as a package price is badly wrong:
 * METRO's 15kg frozen fillet at 571,99 would come out at 38 dinars a kilo.
 * The same repetition WITHOUT "cca" means something else entirely — a 200g box
 * of pralines really does cost what the row says — and that case is far more
 * common, so the correction must not touch it.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class VariableWeightPricingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    private static final String CSV = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            8;Meso;CCA 15KG ZAMRZNUTI PILECI FILE;NO BRAND;2604065701011;KG;Test format;571,99;;13-09-2026;571,99;;;10
            8;Meso;PILECI FILE CCA 3KG VAKUUM;NO BRAND;2604065701012;KG;Test format;698,49;;13-09-2026;698,49;;;10
            9;Slatkisi;Bombonjera Ferrero Rocher T16 200g;Ferrero;2604065701013;kg;Test format;861,99;;13-09-2026;861,99;;;20
            1;Mleko;Mleko 1l;Test;2604065701014;L;Test format;99,99;;13-09-2026;99,99;;;10
            """;

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/prices.csv", exchange -> {
            byte[] body = CSV.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private BigDecimal priceOf(String barcode) {
        return jdbcClient.sql("""
                        SELECT offer.regular_price
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        WHERE product.barcode = ?
                        """)
                .param(barcode)
                .query(BigDecimal.class)
                .single();
    }

    @Test
    void perKilogramPricesBecomePackagePricesOnlyForVariableWeightGoods() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('VARWEIGHT', 'Variable weight test', ?)
                        """)
                .param("http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/prices.csv")
                .update();

        ImportResult result = priceImportService.importPrices("VARWEIGHT");
        assertThat(result.status()).isEqualTo("SUCCEEDED");

        // 571,99 a kilo over roughly 15kg, not 571,99 for the whole box.
        assertThat(priceOf("2604065701011"))
                .isEqualByComparingTo("8579.85");
        assertThat(priceOf("2604065701012"))
                .isEqualByComparingTo("2095.47");

        // No "cca": the row already states what the package costs.
        assertThat(priceOf("2604065701013"))
                .isEqualByComparingTo("861.99");

        // Exactly one unit of measure: nothing to scale either way.
        assertThat(priceOf("2604065701014"))
                .isEqualByComparingTo("99.99");
    }
}
