package rs.pametnakupovina.backend.dataquality;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.product.ProductCatalogMaintenanceService;
import rs.pametnakupovina.backend.product.ProductReportReason;
import rs.pametnakupovina.backend.product.ProductReportRequest;
import rs.pametnakupovina.backend.product.ProductReportService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One chain sells Zaječarsko's jubilee bottle under an old barcode and a
 * name no rule reads as the other chain's "NRGB" bottle. Brand and size
 * match and, once read off, the two names say nothing else — same brand
 * and size is the same product (V99), so that pair is decided the moment
 * both price lists are in, nobody is asked. Vidal's candy turtles are
 * different: one chain's name says "GUMENE" where the other's does not, an
 * extra word neither side's rule can safely drop, so that pair is still
 * asked about — and not the dark beer, a real difference no rule blurs. The
 * candies are rejected: after the next rebuild the bottles are one product,
 * the candies are still two, and nothing is asked again. A shopper's report
 * is reviewed too.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class ProductReviewTest {

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

    private static final String JUBILEE = "PIVO ZAJECAR.JUBIL.0.33L ST.NEP.";
    private static final String BOTTLE = "Pivo Zajecarsko 0.33l NRGB";
    private static final String DARK = "PIVO ZAJECARSKO CRNO 0.33L NPB";
    private static final String GUMMY = "BOMBONE GUMENE KORNJAČE 90G VIDAL";
    private static final String CANDY = "Bombone Vidal kornjace 90g";

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
    private ProductReviewService reviewService;

    @Autowired
    private ProductReportService reportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/first.csv", HEADER
                + row("PIVO", JUBILEE, "Zajecarsko", "8606107561103", "79,99")
                + row("BOMBONE", GUMMY, "Vidal", "8412345000011", "129,99"));
        serve("/second.csv", HEADER
                + row("PIVO", BOTTLE, "Zajecarsko", "8606109308928", "84,99")
                + row("PIVO", DARK, "Zajecarsko", "8606109308935", "89,99")
                + row("BOMBONE", CANDY, "Vidal", "8412345000028", "119,99"));
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

    private static boolean pairs(ProductMergeSuggestionReview suggestion, String first, String second) {
        List<String> names = List.of(suggestion.left().name(), suggestion.right().name());
        return names.contains(first) && names.contains(second);
    }

    @Test
    void theOwnerDecidesWhichLookAlikesAreOneProduct() {
        importChain("REVIEW_FIRST", "/first.csv");
        importChain("REVIEW_SECOND", "/second.csv");
        catalogMaintenanceService.refreshAll();

        // Brand and size line up and, brand and packaging code aside, both
        // names say only "pivo" — decided the moment both lists are in,
        // nobody asked (V99).
        assertThat(familyOf(JUBILEE)).isEqualTo(familyOf(BOTTLE));
        assertThat(familyOf(DARK)).isNotEqualTo(familyOf(BOTTLE));

        List<ProductMergeSuggestionReview> suggestions = reviewService.reviewMergeSuggestions(50);
        assertThat(suggestions).hasSize(1);
        ProductMergeSuggestionReview candies = suggestions.stream()
                .filter(suggestion -> pairs(suggestion, GUMMY, CANDY))
                .findFirst()
                .orElseThrow();

        assertThat(reviewService.decideMerge(candies.id(), new ProductMergeDecisionRequest(false)).decision())
                .isEqualTo("DIFFERENT");
        assertThatThrownBy(() -> reviewService.decideMerge(candies.id(), new ProductMergeDecisionRequest(false)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("više ne postoji");

        catalogMaintenanceService.refreshAll();

        assertThat(familyOf(JUBILEE)).isEqualTo(familyOf(BOTTLE));
        assertThat(familyOf(DARK)).isNotEqualTo(familyOf(BOTTLE));
        assertThat(familyOf(GUMMY)).isNotEqualTo(familyOf(CANDY));
        assertThat(reviewService.reviewMergeSuggestions(50)).isEmpty();
    }

    @Test
    void aShoppersReportIsConfirmedOrRejected() {
        long product = jdbcClient.sql("""
                        INSERT INTO app.canonical_product (canonical_key, barcode, name, normalized_name)
                        VALUES ('EAN:8600000000019', '8600000000019', 'Mleko testno 1L', 'mleko testno 1l')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        long reportId = reportService.report(product, null,
                new ProductReportRequest(ProductReportReason.WRONG_PRICE, "Cena je stara", null)).id();

        List<ProductReportReview> reports = reviewService.reviewReports("NEW", 10);
        assertThat(reports).extracting(ProductReportReview::id).contains(reportId);
        assertThat(reports).filteredOn(report -> report.id() == reportId)
                .singleElement()
                .satisfies(report -> {
                    assertThat(report.productName()).isEqualTo("Mleko testno 1L");
                    assertThat(report.note()).isEqualTo("Cena je stara");
                });

        assertThat(reviewService.decideReport(reportId, new ProductReportReviewRequest("confirmed")).status())
                .isEqualTo("CONFIRMED");
        assertThat(reviewService.reviewReports("NEW", 10)).extracting(ProductReportReview::id)
                .doesNotContain(reportId);
        assertThatThrownBy(() -> reviewService.decideReport(reportId, new ProductReportReviewRequest("možda")))
                .isInstanceOf(ResponseStatusException.class);
    }
}
