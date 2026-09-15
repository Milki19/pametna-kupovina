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
 * METRO names a can "0.5L ZAJECARSKO SVET.PIVO LIM-STAND.-VAR." and another
 * chain names the same barcode "Pivo Zaječarsko limenka 0,5l": the product is
 * shown under the second. A name led by a stock code loses the code.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class ProductDisplayNameTest {

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
    private static final String CODED_GEL = "369680 Dove gel za tuširanje 700 ml";

    private static String row(String category, String name, String brand, String barcode, String price) {
        return category + ";" + category + ";" + name + ";" + brand + ";" + barcode + ";KOM;Test format;"
                + price + ";;13-09-2026;" + price + ";;;20\n";
    }

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ProductCatalogMaintenanceService catalogMaintenanceService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/metro.csv", HEADER
                + row("PIVO", METRO_CAN, "ZAJEČARSKO", "8600649000014", "95,00"));
        serve("/shop.csv", HEADER
                + row("PIVO", "Pivo Zaječarsko limenka 0,5l", "Zajecarsko", "8600649000014", "89,99")
                + row("KOZMETIKA", CODED_GEL, "Dove", "8710000000017", "499,99"));
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

    private String displayNameOf(String productName) {
        return jdbcClient.sql("""
                        SELECT family.display_name
                        FROM app.retailer_product AS product
                        JOIN app.product_family AS family
                          ON family.id = product.product_family_id
                        WHERE product.name = ?
                        """)
                .param(productName)
                .query(String.class)
                .single();
    }

    private String clean(String name) {
        return jdbcClient.sql("SELECT app.clean_product_name(?)")
                .param(name)
                .query(String.class)
                .single();
    }

    @Test
    void aProductIsShownUnderItsClearestName() {
        importChain("NAME_METRO", "/metro.csv");
        importChain("NAME_SHOP", "/shop.csv");
        catalogMaintenanceService.refreshAll();

        assertThat(displayNameOf(METRO_CAN)).isEqualTo("Pivo Zaječarsko limenka 0,5l");
        assertThat(displayNameOf(CODED_GEL)).isEqualTo("Dove gel za tuširanje 700 ml");
    }

    @Test
    void metroSuffixesSizesInFrontAndStockCodesAreCut() {
        assertThat(clean(METRO_CAN)).isEqualTo("ZAJECARSKO SVET.PIVO LIM 0.5L");
        assertThat(clean("SKARPINA-STAND.-VAR.")).isEqualTo("SKARPINA");
        assertThat(clean("COK.MILKA OREO 300G-362")).isEqualTo("COK.MILKA OREO 300G");
        assertThat(clean("SHAV.FOAM REGULAR 200-743/925")).isEqualTo("SHAV.FOAM REGULAR 200");
        // Sizes and counts that belong to the product stay.
        assertThat(clean("KESE ZA SM 60L 64X71CM 20/1 ALUFIX")).isEqualTo("KESE ZA SM 60L 64X71CM 20/1 ALUFIX");
        assertThat(clean("MC ORADA TR 600/800 G-STAND.-VAR.")).isEqualTo("MC ORADA TR 600/800 G");
        assertThat(clean("PIVO ZAJECARSKO CRNO 0.33-CAN")).isEqualTo("PIVO ZAJECARSKO CRNO 0.33-CAN");
    }
}
