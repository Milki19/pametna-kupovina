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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Names put together the way Cenoteka writes them: the brand in capitals with
 * Serbian letters, packaging spelled out, the size last. The chains' own names
 * stay as they were.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class ComposedNameTest {

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

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static final String METRO_CAN = "0.5L ZAJECARSKO SVET.PIVO LIM-STAND.-VAR.";
    private static final String ONE_WAY_BOTTLE = "Pivo Zajecarsko 0.33l NRGB";
    private static final String FOUR_CANS = "PIVO ZAJECARSKO 4x0.5l CAN";
    private static final String CELERY = "CELER KOREN";
    private static final String GEL = "369680 Dove gel za tuširanje 700 ml";
    private static final String PADS = "ULOŠCI NOĆNI BIO 7/1 BELLA";

    private static String row(String name, String brand, String barcode, String price) {
        return "PIVO;Pivo;" + name + ";" + brand + ";" + barcode + ";KOM;Test format;"
                + price + ";;13-09-2026;" + price + ";;;20\n";
    }

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/metro.csv", HEADER
                + row(METRO_CAN, "ZAJEČARSKO", "8600649000021", "95,00"));
        serve("/shop.csv", HEADER
                + row("PIVO ZAJEČARSKO LIMENKA 0,5L", "ZAJEČARSKO", "8600649000021", "89,99")
                + row(ONE_WAY_BOTTLE, "Zajecarsko", "8606109308911", "79,99")
                + row(FOUR_CANS, "Zajecarsko", "8600649000038", "359,99")
                + row(CELERY, "", "", "129,99")
                + row(GEL, "Dove", "8710000000024", "499,99")
                + row(PADS, "Bella", "5900516300018", "189,99"));
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    private static void serve(String path, String csv) {
        csvServer.createContext(path, exchange -> {
            byte[] body = csv.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private void importChain(String code, String path) {
        jdbcClient.sql("INSERT INTO app.retailer (code, name, dataset_url) VALUES (?, ?, ?)")
                .param(code)
                .param(code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .update();
        assertThat(priceImportService.importPrices(code).status()).isEqualTo("SUCCEEDED");
    }

    private String composedNameOf(String productName) {
        return jdbcClient.sql("""
                        SELECT family.composed_name
                        FROM app.retailer_product AS product
                        JOIN app.product_family AS family
                          ON family.id = product.product_family_id
                        WHERE product.name = ?
                        """)
                .param(productName)
                .query(String.class)
                .single();
    }

    @Test
    void productsAreNamedTheWayCenotekaWritesThem() {
        importChain("COMPOSED_METRO", "/metro.csv");
        importChain("COMPOSED_SHOP", "/shop.csv");

        assertThat(composedNameOf(METRO_CAN)).isEqualTo("ZAJEČARSKO pivo limenka 0,5 l");
        assertThat(composedNameOf(ONE_WAY_BOTTLE)).isEqualTo("ZAJEČARSKO pivo nepovratna flaša 0,33 l");
        assertThat(composedNameOf(FOUR_CANS)).isEqualTo("ZAJEČARSKO pivo limenka 4 × 0,5 l");
        assertThat(composedNameOf(CELERY)).isEqualTo("Celer koren");
        assertThat(composedNameOf(GEL)).isEqualTo("DOVE gel za tuširanje 0,7 l");
        assertThat(composedNameOf(PADS)).isEqualTo("BELLA ulošci noćni bio 7 kom");

        // The chain's own name is kept.
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.retailer_product WHERE name = ?")
                .param(METRO_CAN)
                .query(Integer.class)
                .single()).isEqualTo(1);
    }
}
