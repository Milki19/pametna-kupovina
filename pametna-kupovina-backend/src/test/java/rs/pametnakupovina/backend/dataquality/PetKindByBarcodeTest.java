package rs.pametnakupovina.backend.dataquality;

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
import rs.pametnakupovina.backend.product.ProductCatalogMaintenanceService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Friskies makes food for cats and for dogs, so the brand alone names no
 * animal (V154). Pet food whose name names none is the kind the same barcode
 * has at other chains: three chains that say "za pse" outvote one that does
 * not. A barcode no other chain knows gets no kind rather than a guess, and
 * the next catalogue refresh keeps all of it.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PetKindByBarcodeTest {

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

    private static final String DOG_KIBBLE_BARCODE = "7613031395460";
    private static final String CAT_POUCH_BARCODE = "7613036962254";

    private static final String DOG_KIBBLE_NAMELESS = "FRISKIES GRANULE ACTIVE 0.5KG";
    private static final String CAT_POUCH_NAMELESS = "FRISKIES GOVEDINA 85G";
    private static final String DOG_KIBBLE_UNKNOWN = "FRISKIES BALANCE 2,4KG NESTLE PURINA";
    private static final String DOG_TREAT_CALLED_CAT = "HRANA ZA MACKE CESAR PILETINA 150G";
    private static final String DOG_TREAT_BARCODE = "4008429087554";

    private static String row(String name, String barcode) {
        return "25;Hrana za kućne ljubimce;" + name + ";Testna;" + barcode + ";KOM;Test format;"
                + "299,99;;13-09-2026;299,99;;;20\n";
    }

    private static String namedChain(int number) {
        return HEADER
                + row("HRANA ZA PSE FRISKIES ACTIVE 500G " + number, DOG_KIBBLE_BARCODE)
                + row("FRISKIES PRELIV ZA MACKE GOVEDINA 85G " + number, CAT_POUCH_BARCODE)
                + row("CESAR ZA PSE PILETINA 150G " + number, DOG_TREAT_BARCODE);
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
        for (int number = 1; number <= 3; number++) {
            serve("/named-" + number + ".csv", namedChain(number));
        }
        serve("/nameless.csv", HEADER
                + row(DOG_KIBBLE_NAMELESS, DOG_KIBBLE_BARCODE)
                + row(CAT_POUCH_NAMELESS, CAT_POUCH_BARCODE)
                + row(DOG_KIBBLE_UNKNOWN, "7613035999992")
                + row(DOG_TREAT_CALLED_CAT, DOG_TREAT_BARCODE));
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

    private String typeOf(String productName) {
        return jdbcClient.sql("""
                        SELECT type.code
                        FROM app.retailer_product AS product
                        LEFT JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        LEFT JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        WHERE product.name = ?
                        """)
                .param(productName)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void importChain(String code, String path) {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .update();
        assertThat(priceImportService.importPrices(code).status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void theSameBarcodeAtOtherChainsNamesTheAnimal() {
        for (int number = 1; number <= 3; number++) {
            importChain("NAMED_" + number, "/named-" + number + ".csv");
        }
        assertThat(typeOf("HRANA ZA PSE FRISKIES ACTIVE 500G 1")).isEqualTo("DOG_FOOD");
        assertThat(typeOf("FRISKIES PRELIV ZA MACKE GOVEDINA 85G 1")).isEqualTo("CAT_FOOD");

        importChain("NAMELESS", "/nameless.csv");
        assertThat(typeOf(DOG_KIBBLE_NAMELESS)).isEqualTo("DOG_FOOD");
        assertThat(typeOf(CAT_POUCH_NAMELESS)).isEqualTo("CAT_FOOD");
        // One chain calling dog food "za mačke" is outvoted by three.
        assertThat(typeOf(DOG_TREAT_CALLED_CAT)).isEqualTo("DOG_FOOD");
        // No other chain knows it, and the brand is no longer cats only.
        assertThat(typeOf(DOG_KIBBLE_UNKNOWN)).isNull();

        catalogMaintenanceService.refreshAll();
        assertThat(typeOf(DOG_KIBBLE_NAMELESS)).isEqualTo("DOG_FOOD");
        assertThat(typeOf(CAT_POUCH_NAMELESS)).isEqualTo("CAT_FOOD");
        assertThat(typeOf(DOG_TREAT_CALLED_CAT)).isEqualTo("DOG_FOOD");
        assertThat(typeOf("HRANA ZA PSE FRISKIES ACTIVE 500G 2")).isEqualTo("DOG_FOOD");
        assertThat(typeOf(DOG_KIBBLE_UNKNOWN)).isNull();
    }
}
