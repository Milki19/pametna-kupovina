package rs.pametnakupovina.backend.product;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.dataquality.ProductMergeSuggestionReview;
import rs.pametnakupovina.backend.dataquality.ProductReviewService;
import rs.pametnakupovina.backend.matching.NameMeaning;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vidal's candy turtles stay an open "Isti proizvodi?" pair (the gummy word
 * is on one side only). With a name-meaning model the pair also gets how
 * close the two names are in meaning (V135), shown to the owner next to the
 * letters score. Each name is read by the model once: the next catalog
 * refresh only multiplies the stored vectors.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class MergeSuggestionMeaningTest {

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

    private static final String GUMMY = "BOMBONE GUMENE KORNJAČE 90G VIDAL";
    private static final String CANDY = "Bombone Vidal kornjace 90g";

    private static final AtomicInteger NAMES_READ = new AtomicInteger();

    private static String row(String name, String barcode, String price) {
        return "BOMBONE;BOMBONE;" + name + ";Vidal;" + barcode + ";KOM;Test format;"
                + price + ";;13-09-2026;" + price + ";;;20\n";
    }

    private static HttpServer csvServer;

    @TestConfiguration
    static class Meaning {

        /** "gumene" is one direction, every name shares another: the turtles meet at 1/√2. */
        @Bean
        @Primary
        NameMeaning testNameMeaning() {
            return new NameMeaning() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public String modelCode() {
                    return "test-model";
                }

                @Override
                public List<float[]> vectors(List<String> names) {
                    NAMES_READ.addAndGet(names.size());
                    List<float[]> vectors = new ArrayList<>();
                    for (String name : names) {
                        boolean gummy = name.toLowerCase(Locale.ROOT).contains("gumene");
                        float side = (float) Math.sqrt(0.5);
                        vectors.add(gummy ? new float[]{side, side, 0} : new float[]{1, 0, 0});
                    }
                    return vectors;
                }
            };
        }
    }

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ProductCatalogMaintenanceService catalogMaintenanceService;

    @Autowired
    private ProductReviewService reviewService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/first.csv", HEADER + row(GUMMY, "8412345000011", "129,99"));
        serve("/second.csv", HEADER + row(CANDY, "8412345000028", "119,99"));
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

    @Test
    void anOpenPairIsScoredByMeaningAndEachNameIsReadOnce() {
        importChain("MEANING_FIRST", "/first.csv");
        importChain("MEANING_SECOND", "/second.csv");
        catalogMaintenanceService.refreshAll();

        List<ProductMergeSuggestionReview> suggestions = reviewService.reviewMergeSuggestions(50);
        assertThat(suggestions).singleElement().satisfies(pair -> {
            assertThat(List.of(pair.left().name(), pair.right().name())).containsExactlyInAnyOrder(GUMMY, CANDY);
            assertThat(pair.meaningScore()).isEqualByComparingTo(new BigDecimal("0.7071"));
            assertThat(pair.score()).isNotNull();
        });
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM app.product_family_meaning WHERE model_code = 'test-model'")
                .query(Long.class).single()).isEqualTo(2);

        int read = NAMES_READ.get();
        catalogMaintenanceService.refreshAll();

        // The pair is built again on every refresh; its names are not read again.
        assertThat(NAMES_READ.get()).isEqualTo(read);
        assertThat(reviewService.reviewMergeSuggestions(50)).singleElement()
                .satisfies(pair -> assertThat(pair.meaningScore()).isEqualByComparingTo(new BigDecimal("0.7071")));
    }
}
