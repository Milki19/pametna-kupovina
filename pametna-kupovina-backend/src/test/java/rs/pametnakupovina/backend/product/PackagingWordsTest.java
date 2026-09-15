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
 * Zaječarsko 0,33 l in a one-way bottle, as three chains write it: "NRGB"
 * under the brand Zajecarsko, "NB" and "SVETLO" under ZAJEČARSKO, and "NB"
 * under Univerexport's shortened "ZAJECARS". It is one product. The dark beer
 * of the brand is another.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PackagingWordsTest {

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

    private static String row(String name, String brand, String price) {
        return "PIVO;Pivo;" + name + ";" + brand + ";;KOM;Test format;" + price
                + ";;13-09-2026;" + price + ";;;20\n";
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
        serve("/maxi.csv", HEADER + row("Pivo Zajecarsko 0.33l NRGB", "Zajecarsko", "89,99"));
        serve("/metro.csv", HEADER
                + row("0.33L ZAJECARSKO PIVO SVETLO NB-STAND.-VAR.", "ZAJEČARSKO", "95,00")
                + row("PIVO ZAJEČARSKO CRNO 0,33L NPB", "ZAJEČARSKO", "99,99"));
        serve("/univerexport.csv", HEADER + row("PIVO ZAJEČARSKO 0.33L NB", "ZAJECARS", "79,99"));
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

    private Long familyOf(String name) {
        return jdbcClient.sql("SELECT product_family_id FROM app.retailer_product WHERE name = ?")
                .param(name)
                .query(Long.class)
                .single();
    }

    @Test
    void oneBottleUnderEveryChainsAbbreviationIsOneProduct() {
        importChain("MAXI_TEST", "/maxi.csv");
        importChain("METRO_TEST", "/metro.csv");
        importChain("UNIVEREXPORT_TEST", "/univerexport.csv");
        // The known Univerexport reads its feed from the government catalogue;
        // the test chain takes its code once its prices are in.
        jdbcClient.sql("UPDATE app.retailer SET code = 'UNIVEREXPORT_KNOWN' WHERE code = 'UNIVEREXPORT'").update();
        jdbcClient.sql("UPDATE app.retailer SET code = 'UNIVEREXPORT' WHERE code = 'UNIVEREXPORT_TEST'").update();
        catalogMaintenanceService.refreshAll();

        Long bottle = familyOf("Pivo Zajecarsko 0.33l NRGB");
        assertThat(familyOf("0.33L ZAJECARSKO PIVO SVETLO NB-STAND.-VAR.")).isEqualTo(bottle);
        assertThat(familyOf("PIVO ZAJEČARSKO 0.33L NB")).isEqualTo(bottle);
        assertThat(familyOf("PIVO ZAJEČARSKO CRNO 0,33L NPB")).isNotEqualTo(bottle);

        // The shortened brand now resolves to the full one on every import.
        assertThat(jdbcClient.sql("""
                        SELECT app.product_match_brand_key(brand.display_name)
                        FROM app.retailer_product AS product
                        JOIN app.brand AS brand ON brand.id = product.brand_id
                        WHERE product.name = 'PIVO ZAJEČARSKO 0.33L NB'
                        """)
                .query(String.class)
                .single()).isEqualTo("zajecarsko");
    }
}
