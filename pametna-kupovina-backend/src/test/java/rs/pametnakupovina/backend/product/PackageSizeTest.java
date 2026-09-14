package rs.pametnakupovina.backend.product;

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
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Package sizes as chains really write them. Each case below kept a product
 * apart from the same product elsewhere or showed a wrong size in the app:
 * a size a thousand times too small, a drink with no unit, a multipack whose
 * count was lost, and a case of eight sold under the name of one bottle.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PackageSizeTest {

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

    private static final String CSV = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n" + """
            PIVO;Pivo;PIVO LAV 0.5 CAN;Lav;;KOM;Test format;99,99;;13-09-2026;199,98;;;20
            2;Pica;RED BULL 0.355ML;Red Bull;;KOM;Test format;229,90;;13-09-2026;647,61;;;20
            3;Hleb;HLEB TONUS HELJDA 0.300GR;Tonus;;KOM;Test format;229,90;;13-09-2026;766,33;;;10
            4;Pica;Voda Vrnjci 2x1.5l;Vrnjci;;KOM;Test format;159,99;;13-09-2026;53,33;;;20
            5;Pica;2L COCA COLA SOK GAZ PET;Coca Cola;8601234000051;L;Test format;191,00;;13-09-2026;95,50;;;20
            6;Pica;2L COCA COLA SOK GAZ PET;Coca Cola;8601234000068;L;Test format;1528,00;;13-09-2026;95,50;;;20
            """;

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/sizes.csv", exchange -> {
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

    private Map<String, Object> size(String condition, Object value) {
        return jdbcClient.sql(
                        "SELECT quantity_value, base_unit, package_count"
                                + " FROM app.retailer_product WHERE "
                                + condition
                )
                .param(value)
                .query()
                .singleRow();
    }

    private static void assertSize(
            Map<String, Object> row,
            String quantity,
            String unit,
            int packageCount
    ) {
        assertThat((BigDecimal) row.get("quantity_value"))
                .isEqualByComparingTo(quantity);
        assertThat(row.get("base_unit")).isEqualTo(unit);
        assertThat(row.get("package_count")).isEqualTo(packageCount);
    }

    @Test
    void sizesAreReadTheWayChainsWriteThem() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('PACK_SIZES', 'Package sizes test', ?)
                        """)
                .param("http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/sizes.csv")
                .update();

        assertThat(priceImportService.importPrices("PACK_SIZES").status())
                .isEqualTo("SUCCEEDED");

        assertSize(size("name = ?", "PIVO LAV 0.5 CAN"), "500", "ml", 1);
        assertSize(size("name = ?", "RED BULL 0.355ML"), "355", "ml", 1);
        assertSize(size("name = ?", "HLEB TONUS HELJDA 0.300GR"), "300", "g", 1);
        // Two packs of a litre and a half: the price is for both.
        assertSize(size("name = ?", "Voda Vrnjci 2x1.5l"), "3000", "ml", 2);
        // One bottle, and a case of eight that costs exactly eight bottles.
        assertSize(size("barcode = ?", "8601234000051"), "2000", "ml", 1);
        assertSize(size("barcode = ?", "8601234000068"), "16000", "ml", 8);
    }
}
