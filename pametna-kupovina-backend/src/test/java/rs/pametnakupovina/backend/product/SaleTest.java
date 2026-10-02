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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A chain's list of today has four biscuits with a lower price next to the
 * regular one: a sale that runs this week, a sale that ended yesterday (the
 * chain has not replaced its list yet), a "sale" at a twentieth of the price
 * (a typo) and a "sale" above the regular price. Only the first is a sale:
 * search shows it with its discount, the list of sales has only it, and every
 * other price is the regular one.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class SaleTest {

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

    private static final String ROW =
            "KEKS;Keks;%s;Testna;%s;KOM;Test format;%s;%s;%s;%s;%s;%s;20\n";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private static volatile String catalogue = "";
    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private CanonicalProductSearchService searchService;

    @Autowired
    private SaleService saleService;

    @Autowired
    private SaleListRefresher saleListRefresher;

    @Autowired
    private CanonicalProductDetailsRepository detailsRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/sale.csv", exchange -> {
            byte[] body = catalogue.getBytes(StandardCharsets.UTF_8);
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

    private static String row(String name, String barcode, String regular, String discounted,
                              LocalDate today, LocalDate from, LocalDate to) {
        return ROW.formatted(name, barcode, regular, discounted, today.format(DAY), regular,
                from.format(DAY), to.format(DAY));
    }

    private ProductRetailerAvailability chainPrice(String word) {
        var page = searchService.search(word, 0, 10, false, null, false);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().availability()).hasSize(1);
        return page.items().getFirst().availability().getFirst();
    }

    @Test
    void onlyASaleThatLastsTodayIsASale() {
        LocalDate today = LocalDate.now(ZoneId.of("Europe/Belgrade"));
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('SALE', 'Sale', ?)
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/sale.csv")
                .update();

        catalogue = HEADER
                + row("KEKS PRVOAKCIJSKI 200G", "8601234500100", "200,00", "150,00",
                        today, today.minusDays(2), today.plusDays(5))
                + row("KEKS DRUGOISTEKAO 200G", "8601234500117", "100,00", "80,00",
                        today, today.minusDays(9), today.minusDays(1))
                + row("KEKS TRECEGRESKA 200G", "8601234500124", "100,00", "5,00",
                        today, today.minusDays(2), today.plusDays(5))
                + row("KEKS CETVRTOVISE 200G", "8601234500131", "100,00", "120,00",
                        today, today.minusDays(2), today.plusDays(5));
        assertThat(priceImportService.importPrices("SALE").status()).isEqualTo("SUCCEEDED");
        // The list of sales is kept ready; the scheduler would refresh it
        // within minutes of the import.
        saleListRefresher.refresh();

        ProductRetailerAvailability onSale = chainPrice("prvoakcijski");
        assertThat(onSale.minimumEffectivePrice()).isEqualByComparingTo("150.00");
        assertThat(onSale.saleRegularPrice()).isEqualByComparingTo("200.00");
        assertThat(onSale.discountPercent()).isEqualTo(25);
        assertThat(onSale.saleEndDate()).isEqualTo(today.plusDays(5));

        for (String word : new String[]{"drugoistekao", "trecegreska", "cetvrtovise"}) {
            ProductRetailerAvailability regular = chainPrice(word);
            assertThat(regular.minimumEffectivePrice()).as(word).isEqualByComparingTo("100.00");
            assertThat(regular.discountPercent()).as(word).isNull();
            assertThat(regular.saleRegularPrice()).as(word).isNull();
        }

        // The chain's lowest price kept for the product is the regular one too.
        assertThat(jdbcClient.sql("""
                        SELECT presence.minimum_effective_price
                        FROM app.product_retailer_presence AS presence
                        JOIN app.retailer_product AS product
                          ON product.product_family_id = presence.product_family_id
                        WHERE product.name = 'KEKS DRUGOISTEKAO 200G'
                        """)
                .query(java.math.BigDecimal.class)
                .single()).isEqualByComparingTo("100.00");

        long saleFamily = familyId("KEKS PRVOAKCIJSKI 200G");
        assertThat(searchService.search("keks", 0, 10, false, null, true).items())
                .extracting(CanonicalProductSearchItem::productFamilyId)
                .containsExactly(saleFamily);

        SalePage sales = saleService.find(null, null, null, null, 1, SaleSort.DISCOUNT, 0, 20);
        assertThat(sales.items()).singleElement().satisfies(sale -> {
            assertThat(sale.productFamilyId()).isEqualTo(saleFamily);
            assertThat(sale.retailerCode()).isEqualTo("SALE");
            assertThat(sale.salePrice()).isEqualByComparingTo("150.00");
            assertThat(sale.regularPrice()).isEqualByComparingTo("200.00");
            assertThat(sale.discountPercent()).isEqualTo(25);
            assertThat(sale.saleEndDate()).isEqualTo(today.plusDays(5));
            assertThat(sale.otherChainCount()).isZero();
            assertThat(sale.canonicalProductId()).isNotNull();
        });
        assertThat(sales.totalElements()).isEqualTo(1);
        assertThat(sales.categories()).isNotEmpty();
        assertThat(saleService.find(null, null, null, "prvoakcijski", 1, SaleSort.PRICE, 0, 20).items()).hasSize(1);
        assertThat(saleService.find(null, null, null, "drugoistekao", 1, SaleSort.PRICE, 0, 20).items()).isEmpty();
        assertThat(saleService.find(null, null, null, null, 30, SaleSort.SAVING, 0, 20).items()).isEmpty();

        // The product screen shows the sale until when, and an ended sale as
        // the regular price.
        long saleProduct = canonicalId("KEKS PRVOAKCIJSKI 200G");
        assertThat(detailsRepository.findLatestOffers(saleProduct, today)).singleElement().satisfies(offer -> {
            assertThat(offer.effectivePrice()).isEqualByComparingTo("150.00");
            assertThat(offer.saleEndDate()).isEqualTo(today.plusDays(5));
        });
        assertThat(detailsRepository.findLatestOffers(canonicalId("KEKS DRUGOISTEKAO 200G"), today))
                .singleElement().satisfies(offer -> {
                    assertThat(offer.effectivePrice()).isEqualByComparingTo("100.00");
                    assertThat(offer.saleEndDate()).isNull();
                });
    }

    private long familyId(String name) {
        return jdbcClient.sql("SELECT product_family_id FROM app.retailer_product WHERE name = ?")
                .param(name)
                .query(Long.class)
                .single();
    }

    private long canonicalId(String name) {
        return jdbcClient.sql("SELECT canonical_product_id FROM app.retailer_product WHERE name = ?")
                .param(name)
                .query(Long.class)
                .single();
    }
}
