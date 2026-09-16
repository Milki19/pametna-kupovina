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
import rs.pametnakupovina.backend.shoppinglist.AddShoppingListItemRequest;
import rs.pametnakupovina.backend.shoppinglist.CreateShoppingListRequest;
import rs.pametnakupovina.backend.shoppinglist.FlexibleItemConstraints;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemRule;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.shoppinglist.StoreItemOffer;
import rs.pametnakupovina.backend.shoppinglist.StoreShoppingOfferRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "so", "riba", "meso", "voće" and "povrće" on a list find products: a product
 * gets the type when its name and the chain's category agree. Galettes with sea
 * salt, cat food with salmon, dog pâté and sauerkraut do not. A type removed on
 * the admin page stays removed after the next catalogue refresh.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class GenericProductTypeTest {

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

    private static final String SALT = "SO KUHINJSKA 1KG SOPRODUKT";
    private static final String GALETTES = "KUKURUZNE GALETE MORSKA SO 130G";
    private static final String TUNA = "TUNA KOMADI U ULJU 160G BARBA";
    private static final String CAT_FOOD = "SHEBA KESICA LOSOS SOS 85G";
    private static final String SALAMI = "SALAMA BUDIMSKA 100G ZLATIBORAC";
    private static final String DOG_PATE = "PASTETA ZA PSE 300G";
    private static final String APPLE = "JABUKA GLOSTER";
    private static final String SAUERKRAUT = "KUPUS KISELI RIBANAC 500G";

    private static final LocalDate PRICE_DAY = LocalDate.of(2026, 9, 13);

    private static String row(String code, String category, String name, String barcode, String price) {
        return code + ";" + category + ";" + name + ";Testna;" + barcode + ";KOM;Test format;"
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
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        String csv = HEADER
                + row("18", "So i začini", SALT, "8600000001011", "59,99")
                + row("18", "So i začini", GALETTES, "8600000001028", "129,99")
                + row("9", "Riba", TUNA, "8600000001035", "189,99")
                + row("9", "Riba", CAT_FOOD, "8600000001042", "79,99")
                + row("8", "Meso", SALAMI, "8600000001059", "149,99")
                + row("8", "Meso", DOG_PATE, "8600000001066", "199,99")
                + row("3", "Voće i povrće", APPLE, "8600000001073", "119,99")
                + row("3", "Voće i povrće", SAUERKRAUT, "8600000001080", "139,99");
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/generic.csv", exchange -> {
            byte[] body = csv.getBytes(StandardCharsets.UTF_8);
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

    private long productId(String productName) {
        return jdbcClient.sql("SELECT id FROM app.retailer_product WHERE name = ?")
                .param(productName)
                .query(Long.class)
                .single();
    }

    @Test
    void everydayWordsFindProductsAndARemovedTypeStaysRemoved() {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('GENERIC', 'Generic', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/generic.csv")
                .query(Long.class)
                .single();
        assertThat(priceImportService.importPrices("GENERIC").status()).isEqualTo("SUCCEEDED");

        assertThat(typeOf(SALT)).isEqualTo("SALT");
        assertThat(typeOf(TUNA)).isEqualTo("FISH");
        assertThat(typeOf(SALAMI)).isEqualTo("MEAT");
        assertThat(typeOf(APPLE)).isEqualTo("FRESH_PRODUCE");
        assertThat(typeOf(GALETTES)).isNotEqualTo("SALT");
        assertThat(typeOf(CAT_FOOD)).isNotEqualTo("FISH");
        assertThat(typeOf(DOG_PATE)).isNotEqualTo("MEAT");
        assertThat(typeOf(SAUERKRAUT)).isNotEqualTo("FRESH_PRODUCE");

        // "so" on a list is priced.
        long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'TEST', 'Test format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();
        long shop = jdbcClient.sql("""
                        INSERT INTO app.store (retailer_id, store_format_id, external_code, name, address, city, active)
                        VALUES (?, ?, 'GENERIC-1', 'Generic objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
        var list = shoppingListService.create(new CreateShoppingListRequest("Opšte reči"), "generic");
        shoppingListService.addItem(list.id(), "generic", new AddShoppingListItemRequest(
                "so", "so", null, BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY,
                new FlexibleItemConstraints("so", null, null, null, null)));
        StoreItemOffer salt = offerRepository.findPriceListOffers(list.id(), List.of(shop), PRICE_DAY)
                .stream()
                .filter(offer -> offer.requestedName().equals("so"))
                .findFirst()
                .orElseThrow();
        assertThat(salt.available()).isTrue();
        assertThat(salt.productName()).isEqualTo(SALT);

        // The owner removes the fish type from the tuna on the admin page.
        assertThat(reviewService.reviewTypeAssignments("FISH", "tuna", 10))
                .extracting(ProductTypeAssignmentReview::productName)
                .containsExactly(TUNA);
        long tuna = productId(TUNA);
        assertThat(reviewService.rejectTypeAssignment(tuna, new ProductTypeRejectionRequest("fish")).message())
                .contains("Uklonjeno");
        assertThat(typeOf(TUNA)).isNull();
        assertThatThrownBy(() -> reviewService.rejectTypeAssignment(tuna, new ProductTypeRejectionRequest("FISH")))
                .isInstanceOf(ResponseStatusException.class);

        catalogMaintenanceService.refreshAll();
        assertThat(typeOf(TUNA)).isNull();
        assertThat(typeOf(SALT)).isEqualTo("SALT");
    }
}
