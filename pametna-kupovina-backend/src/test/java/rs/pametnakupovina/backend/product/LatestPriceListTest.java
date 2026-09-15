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

/**
 * A chain lists milk and yogurt on the 13th and only milk on the 14th. The
 * yogurt keeps its price of the 13th in current_price_offer, but the chain no
 * longer sells it at that price: a plan, the product screen and search must
 * not count it. A plan for the 13th still does.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class LatestPriceListTest {

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

    private static final String MILK =
            "MLEKO;Mleko;MLEKO TESTNO 2.8%% 1L;Testna;8601234500018;KOM;Test format;%s;;%s;%s;;;10\n";
    private static final String YOGURT =
            "JOGURT;Jogurt;JOGURT TESTNI 2.8%% 1KG;Testna;8601234500025;KOM;Test format;%s;;%s;%s;;;10\n";

    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 9, 13);
    private static final LocalDate SECOND_DAY = LocalDate.of(2026, 9, 14);

    private static volatile String catalogue = "";
    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private CanonicalProductDetailsRepository detailsRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/latest.csv", exchange -> {
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

    private void publish(String... rows) {
        catalogue = HEADER + String.join("", rows);
        assertThat(priceImportService.importPrices("LATEST").status())
                .isEqualTo("SUCCEEDED");
    }

    private StoreItemOffer offerFor(Long listId, String name, long shop, LocalDate date) {
        return offerRepository.findPriceListOffers(listId, List.of(shop), date)
                .stream()
                .filter(offer -> offer.requestedName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aProductMissingFromTheNewestListHasNoPrice() {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('LATEST', 'Latest', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/latest.csv")
                .query(Long.class)
                .single();

        publish(MILK.formatted("100,00", "13-09-2026", "100,00"),
                YOGURT.formatted("150,00", "13-09-2026", "150,00"));
        publish(MILK.formatted("105,00", "14-09-2026", "105,00"));

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
                        VALUES (?, ?, 'LATEST-1', 'Latest objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();

        var list = shoppingListService.create(new CreateShoppingListRequest("Poslednji cenovnik"), "latest");
        for (String name : List.of("mleko", "jogurt")) {
            shoppingListService.addItem(list.id(), "latest", new AddShoppingListItemRequest(
                    name, name, null, BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY,
                    new FlexibleItemConstraints(name, null, null, null, null)));
        }

        StoreItemOffer milk = offerFor(list.id(), "mleko", shop, SECOND_DAY);
        assertThat(milk.available()).isTrue();
        assertThat(milk.lineTotal()).isEqualByComparingTo("105.00");
        assertThat(offerFor(list.id(), "jogurt", shop, SECOND_DAY).available()).isFalse();

        // What was known on the 13th still plans the 13th.
        StoreItemOffer yogurtThen = offerFor(list.id(), "jogurt", shop, FIRST_DAY);
        assertThat(yogurtThen.available()).isTrue();
        assertThat(yogurtThen.lineTotal()).isEqualByComparingTo("150.00");

        long yogurtProduct = jdbcClient.sql("""
                        SELECT canonical_product_id FROM app.retailer_product
                        WHERE name = 'JOGURT TESTNI 2.8% 1KG'
                        """)
                .query(Long.class)
                .single();
        assertThat(detailsRepository.findLatestOffers(yogurtProduct, SECOND_DAY)).isEmpty();
        assertThat(detailsRepository.findLatestOffers(yogurtProduct, FIRST_DAY)).hasSize(1);

        assertThat(jdbcClient.sql("""
                        SELECT COUNT(*) FROM app.product_retailer_presence AS presence
                        JOIN app.retailer_product AS product
                          ON product.product_family_id = presence.product_family_id
                        WHERE product.name = 'JOGURT TESTNI 2.8% 1KG'
                        """)
                .query(Integer.class)
                .single()).isZero();
    }
}
