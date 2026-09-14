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
 * Two chains write one product differently: another word order, "50G-CARNE"
 * for "50G CARNEX", a department label or nothing in the brand column. Once
 * both price lists are in, it must be one product with a price from each
 * chain, while products that only look alike stay apart: another brand is
 * another product, and so is another variant of the same brand and size.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class ProductFamilyMergeTest {

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

    private static final String FIRST_CHAIN = HEADER + """
            1;Povrce;CELER KOREN;povrće;;KG;Test format;129,99;;13-09-2026;129,99;;;10
            2;Pastete;PAŠTETA JETRENA 50G CARNEX;Carnex;8601234000013;KOM;Test format;89,99;;13-09-2026;89,99;;;20
            3;Meso;SVINJSKI VRAT SK;Trlić;;KG;Test format;699,99;;13-09-2026;699,99;;;10
            4;Bebe;HIPP COMBIOTIC 1 300G;Hipp;8601234000037;KOM;Test format;999,99;;13-09-2026;999,99;;;20
            5;Povrce;KARFIOL;povrće;;KG;Test format;189,99;;13-09-2026;189,99;;;10
            """;

    private static final String SECOND_CHAIN = HEADER + """
            5;Povrce;Karfiol, rinfuz 1kg;;;kom.;Test format;179,99;;13-09-2026;179,99;;;10
            1;Povrce;Celer koren;;;KG;Test format;119,99;;13-09-2026;119,99;;;10
            2;Pastete;PASTETA JETRENA 50G-CARNE;Carnex;8601234000020;KOM;Test format;84,99;;13-09-2026;84,99;;;20
            3;Meso;VRAT SVINJSKI SK;Vero;;KG;Test format;649,99;;13-09-2026;649,99;;;10
            4;Bebe;HIPP COMBIOTIC 2 300G;Hipp;8601234000044;KOM;Test format;979,99;;13-09-2026;979,99;;;20
            """;

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/first.csv", FIRST_CHAIN);
        serve("/second.csv", SECOND_CHAIN);
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

    private void addRetailer(String code, String path) {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + path)
                .update();
    }

    private long familyOf(String retailerCode, String productName) {
        return jdbcClient.sql("""
                        SELECT product.product_family_id
                        FROM app.retailer_product AS product
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        WHERE retailer.code = ?
                          AND product.name = ?
                        """)
                .param(retailerCode)
                .param(productName)
                .query(Long.class)
                .single();
    }

    private long chainsWithPrice(long familyId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.product_retailer_presence
                        WHERE product_family_id = ?
                        """)
                .param(familyId)
                .query(Long.class)
                .single();
    }

    @Test
    void oneProductWrittenTwoWaysIsOneProductWithBothChainsPrices() {
        addRetailer("MERGE_ONE", "/first.csv");
        addRetailer("MERGE_TWO", "/second.csv");

        assertThat(priceImportService.importPrices("MERGE_ONE").status())
                .isEqualTo("SUCCEEDED");
        assertThat(priceImportService.importPrices("MERGE_TWO").status())
                .isEqualTo("SUCCEEDED");

        long celery = familyOf("MERGE_ONE", "CELER KOREN");
        assertThat(familyOf("MERGE_TWO", "Celer koren")).isEqualTo(celery);
        assertThat(chainsWithPrice(celery)).isEqualTo(2);

        long pate = familyOf("MERGE_ONE", "PAŠTETA JETRENA 50G CARNEX");
        assertThat(familyOf("MERGE_TWO", "PASTETA JETRENA 50G-CARNE"))
                .isEqualTo(pate);
        assertThat(chainsWithPrice(pate)).isEqualTo(2);

        assertThat(familyOf("MERGE_TWO", "VRAT SVINJSKI SK"))
                .isNotEqualTo(familyOf("MERGE_ONE", "SVINJSKI VRAT SK"));
        assertThat(familyOf("MERGE_TWO", "HIPP COMBIOTIC 2 300G"))
                .isNotEqualTo(familyOf("MERGE_ONE", "HIPP COMBIOTIC 1 300G"));
    }
}
