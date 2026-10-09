package rs.pametnakupovina.backend.shoppinglist;

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
import rs.pametnakupovina.backend.TestCallers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * „Nema u prodavnici“ (V121): kad u tri dana dva različita telefona jave da
 * jabuka nema na polici u toj radnji, plan tamo uzima druge jabuke. Jedan
 * telefon nije dovoljan, a druga radnja istog lanca ostaje kakva je bila.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class NotInStoreReportsTest {

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

    private static final String TOKEN = "not-in-store";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String RED_APPLES = "JABUKA CRVENA 2KG";
    private static final String GREEN_APPLES = "JABUKA ZELENA 2KG";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + "3;Sveže voće i povrće;" + RED_APPLES + ";Testna;8600000002011;KOM;Test format;249,99;;13-09-2026;125,00;;;10\n"
            + "3;Sveže voće i povrće;" + GREEN_APPLES + ";Testna;8600000002028;KOM;Test format;279,99;;13-09-2026;140,00;;;10\n";

    private static HttpServer csvServer;

    @Autowired
    private TestCallers callers;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/apples.csv", exchange -> {
            byte[] body = CATALOGUE.getBytes(StandardCharsets.UTF_8);
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

    private List<Long> twoShops() {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('APPLES', 'Apples', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/apples.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("APPLES").status())
                .isEqualTo("SUCCEEDED");

        long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'TEST', 'Test format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        return List.of("APPLES-1", "APPLES-2").stream()
                .map(code -> jdbcClient.sql("""
                                INSERT INTO app.store (retailer_id, store_format_id, external_code, name, address, city, active)
                                VALUES (?, ?, ?, 'Objekat', 'Test adresa', 'Beograd', TRUE)
                                RETURNING id
                                """)
                        .param(retailerId)
                        .param(formatId)
                        .param(code)
                        .query(Long.class)
                        .single())
                .toList();
    }

    /** Imported listings are not matched yet, so the reports point at a product of their own. */
    private long reportedProduct() {
        return jdbcClient.sql("""
                        INSERT INTO app.canonical_product (canonical_key, barcode, name, normalized_name)
                        VALUES ('EAN:8600000002011', '8600000002011', 'Jabuka crvena', 'jabuka crvena')
                        ON CONFLICT (canonical_key) DO UPDATE SET name = EXCLUDED.name
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
    }

    private void reportMissing(String productName, long storeId, String phone) {
        jdbcClient.sql("""
                        INSERT INTO app.product_report (
                            canonical_product_id, retailer_product_id, store_id,
                            reason, client_token_hash, created_at
                        )
                        SELECT COALESCE(product.canonical_product_id, ?), product.id, ?,
                               'NOT_IN_STORE', ?, ?
                        FROM app.retailer_product AS product
                        WHERE product.name = ?
                        """)
                .param(reportedProduct())
                .param(storeId)
                .param(phone)
                .param(PRICE_DATE.atTime(10, 0).atOffset(java.time.ZoneOffset.UTC))
                .param(productName)
                .update();
    }

    private String applesIn(long listId, long storeId) {
        return offerRepository.findPriceListOffers(listId, List.of(storeId), PRICE_DATE)
                .getFirst()
                .productName();
    }

    @Test
    void twoPhonesSayingTheApplesAreMissingSendThePlanToOtherApples() {
        List<Long> shops = twoShops();
        long first = shops.get(0);
        long second = shops.get(1);

        var list = shoppingListService.create(new CreateShoppingListRequest("Voće"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("jabuke")
        );
        assertThat(applesIn(list.id(), first)).isEqualTo(RED_APPLES);

        reportMissing(RED_APPLES, first, "a".repeat(64));
        reportMissing(RED_APPLES, first, "a".repeat(64));
        assertThat(applesIn(list.id(), first)).isEqualTo(RED_APPLES);

        reportMissing(RED_APPLES, first, "b".repeat(64));
        assertThat(applesIn(list.id(), first)).isEqualTo(GREEN_APPLES);
        assertThat(applesIn(list.id(), second)).isEqualTo(RED_APPLES);
    }
}
