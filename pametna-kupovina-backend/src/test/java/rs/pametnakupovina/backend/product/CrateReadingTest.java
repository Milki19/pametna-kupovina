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
 * A cash-and-carry chain names a case of twenty bottles after one bottle and
 * a "3+1" pack of cans after one can, but its price per litre is right for
 * everything it sells by the piece. Two other chains sell the single bottle
 * and can, which is what a case has to cost per piece. A 5 l water whose price
 * per litre is off by ten stays one bottle, because ten bottles for the price
 * of one would be absurd.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class CrateReadingTest {

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

    private static String row(String category, String name, String brand, String price, String unitPrice) {
        return row(category, name, brand, "", price, unitPrice);
    }

    private static String row(String category, String name, String brand, String barcode,
            String price, String unitPrice) {
        return category + ";" + category + ";" + name + ";" + brand + ";" + barcode + ";KOM;Test format;"
                + price + ";;13-09-2026;" + unitPrice + ";;;20\n";
    }

    /** One barcode for the bottle and, at the cash-and-carry chain, the case. */
    private static final String BOTTLE = "8601234500032";

    private static String singles(String zajecarsko, String tuborg, String water) {
        return HEADER
                + row("PIVO", "PIVO ZAJECARSKO 0.5L PB", "Zajecarsko", BOTTLE, zajecarsko, "180,00")
                + row("PIVO", "PIVO TUBORG 0.5L LIMENKA", "Tuborg", tuborg, "260,00")
                + row("VODA", "VODA TESTNA NEGAZIRANA 5L", "Testna voda", water, "30,00");
    }

    private static String cases() {
        StringBuilder csv = new StringBuilder(HEADER);
        // Sold by the piece, with a price per litre that agrees.
        for (int index = 1; index <= 30; index++) {
            String price = (100 + index) + ",00";
            csv.append(row("SOK", "SOK TESTNI " + index + " 1L", "Sokovi " + index, price, price));
        }
        csv.append(row("PIVO", "0.5L ZAJECARSKO SVETLO PIVO PB-STAND.-VAR.", "ZAJEČARSKO", BOTTLE, "1680,00", "168,00"));
        csv.append(row("PIVO", "0.5L TUBORG PIVO LIM 3+1-STAND.-VAR.", "TUBORG", "340,00", "170,00"));
        csv.append(row("VODA", "5L VODA TESTNA NEGAZIRANA-STAND.-VAR.", "Testna voda", "155,00", "3,10"));
        return csv.toString();
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
        serve("/first.csv", singles("94,99", "132,99", "150,00"));
        serve("/second.csv", singles("89,99", "129,99", "145,00"));
        serve("/cases.csv", cases());
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

    private long importChain(String code, String path) {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices(code).status()).isEqualTo("SUCCEEDED");
        return retailerId;
    }

    private Map<String, Object> product(String name) {
        return jdbcClient.sql("""
                        SELECT quantity_value, package_count
                        FROM app.retailer_product
                        WHERE name = ?
                        ORDER BY id
                        LIMIT 1
                        """)
                .param(name)
                .query()
                .singleRow();
    }

    private static void assertSize(Map<String, Object> row, String quantity, int count) {
        assertThat((BigDecimal) row.get("quantity_value")).isEqualByComparingTo(quantity);
        assertThat(row.get("package_count")).isEqualTo(count);
    }

    @Test
    void aCaseIsReadFromThePricePerLitreWhenOneBottleConfirmsIt() {
        importChain("FIRST", "/first.csv");
        importChain("SECOND", "/second.csv");
        long cases = importChain("CASES", "/cases.csv");

        assertSize(product("0.5L ZAJECARSKO SVETLO PIVO PB-STAND.-VAR."), "10000", 20);
        assertSize(product("0.5L TUBORG PIVO LIM 3+1-STAND.-VAR."), "2000", 4);
        assertSize(product("5L VODA TESTNA NEGAZIRANA-STAND.-VAR."), "5000", 1);
        assertSize(product("PIVO ZAJECARSKO 0.5L PB"), "500", 1);

        // The case shares the bottle's barcode and so its product, but it is
        // neither the chain's price for a bottle nor part of what a bottle
        // typically costs: two chains sell the bottle, too few for that.
        // Every chain read again, as the catalogue rebuild does: one chain
        // at a time does not move another chain's bottle to the new key.
        catalogMaintenanceService.refreshAll();
        long bottleFamily = jdbcClient.sql(
                        "SELECT product_family_id FROM app.retailer_product WHERE name = 'PIVO ZAJECARSKO 0.5L PB' LIMIT 1")
                .query(Long.class).single();
        assertThat(jdbcClient.sql(
                        "SELECT product_family_id FROM app.retailer_product WHERE name = '0.5L ZAJECARSKO SVETLO PIVO PB-STAND.-VAR.'")
                .query(Long.class).single()).isEqualTo(bottleFamily);
        assertThat(jdbcClient.sql(
                        "SELECT COUNT(*) FROM app.product_family_typical_price WHERE product_family_id = ?")
                .param(bottleFamily).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql(
                        "SELECT COUNT(*) FROM app.product_retailer_presence WHERE product_family_id = ? AND retailer_id = ?")
                .param(bottleFamily).param(cases).query(Integer.class).single()).isZero();

        // Reading again gives the same sizes.
        catalogMaintenanceService.refreshRetailer(cases);
        assertSize(product("0.5L ZAJECARSKO SVETLO PIVO PB-STAND.-VAR."), "10000", 20);
        assertSize(product("0.5L TUBORG PIVO LIM 3+1-STAND.-VAR."), "2000", 4);
    }
}
