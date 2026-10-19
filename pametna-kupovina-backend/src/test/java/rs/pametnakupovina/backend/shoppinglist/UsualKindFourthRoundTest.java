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
import rs.pametnakupovina.backend.product.CanonicalProductSearchService;
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
 * The fourth round, from the anonymous test of 18.10.2026: "sapun" and
 * "kecap" were not words step 1 knew, "Neka aplikacija izabere" took the
 * smallest hot ketchup, and a search for chicken offered pizza and cat food
 * first (V139).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class UsualKindFourthRoundTest {

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

    private static final String TOKEN = "usual-kind-4";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String BAR_SOAP = "SAPUN DOVE CREAM BAR 90G";
    private static final String LIQUID_SOAP = "TECNI SAPUN PALMOLIVE 500ML";
    private static final String CHILDREN_SOAP = "SAPUN DECIJI 75G";
    private static final String SOAP_DISH = "POSUDA ZA SAPUN KERAMIKA";
    private static final String SOAP_PUMP = "DOZER ZA SAPUN 300ML";
    private static final String BUBBLES = "SAPUNICA MEHURICI 60ML";
    private static final String HOT_KETCHUP = "K PLUS LJUTI 100G KECAP";
    private static final String KETCHUP = "KECAP BLAGI POLIMARK 500G";
    private static final String BIG_KETCHUP = "KECAP BLAGI 1KG";
    private static final String KETCHUP_CRISPS = "CIPS SA UKUSOM KECAPA 150G";
    private static final String CAT_FOOD = "WHISKAS PILETINA 85G";
    private static final String PIZZA = "PICA PILETINA 350G";
    private static final String CHICKEN = "PILETINA CELA 1KG";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + row(BAR_SOAP, "149,99")
            + row(LIQUID_SOAP, "199,99")
            + row(CHILDREN_SOAP, "59,99")
            + row(SOAP_DISH, "39,99")
            + row(SOAP_PUMP, "29,99")
            + row(BUBBLES, "49,99")
            + row(HOT_KETCHUP, "69,99")
            + row(KETCHUP, "219,99")
            + row(BIG_KETCHUP, "399,99")
            + row(KETCHUP_CRISPS, "129,99")
            + row(CAT_FOOD, "79,99")
            + row(PIZZA, "299,99")
            + row(CHICKEN, "699,99");

    private static int barcode = 4000;

    private static String row(String name, String price) {
        barcode++;
        return ";;" + name + ";Testna;86000000" + barcode + ";KOM"
                + ";Test format;" + price + ";;13-09-2026;" + price + ";;;20\n";
    }

    private static HttpServer csvServer;

    @Autowired
    private TestCallers callers;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private ShoppingIntentResolver intentResolver;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private CanonicalProductSearchService searchService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/round4.csv", exchange -> {
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

    private long shop() {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('ROUND4', 'Round four', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/round4.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("ROUND4").status())
                .isEqualTo("SUCCEEDED");

        long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'TEST', 'Test format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        return jdbcClient.sql("""
                        INSERT INTO app.store (retailer_id, store_format_id, external_code, name, address, city, active)
                        VALUES (?, ?, 'ROUND4-1', 'Round four objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    private long existingShop() {
        return jdbcClient.sql("SELECT id FROM app.store WHERE external_code = 'ROUND4-1'")
                .query(Long.class)
                .optional()
                .orElseGet(this::shop);
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

    private String intentOf(String line) {
        return intentResolver.resolve(line)
                .map(ShoppingIntentResolver.ResolvedShoppingIntent::shoppingIntentCode)
                .orElse(null);
    }

    @Test
    void stepOneKnowsSoapAndKetchup() {
        assertThat(intentOf("sapun")).isEqualTo("SOAP");
        assertThat(intentOf("tečni sapun")).isEqualTo("LIQUID_SOAP");
        assertThat(intentOf("kečap")).isEqualTo("KETCHUP");
        assertThat(intentOf("kecap")).isEqualTo("KETCHUP");
        assertThat(intentOf("ketchup")).isEqualTo("KETCHUP");
        assertThat(intentOf("čokolada")).isEqualTo("CHOCOLATE");
        assertThat(intentOf("piletina")).isEqualTo("MEAT");
    }

    @Test
    void theUsualSoapAndKetchupWinOverTheCheaperOnes() {
        long shop = existingShop();

        assertThat(typeOf(BAR_SOAP)).isEqualTo("SOAP");
        assertThat(typeOf(LIQUID_SOAP)).isEqualTo("SOAP");
        assertThat(typeOf(SOAP_DISH)).isNull();
        assertThat(typeOf(SOAP_PUMP)).isNull();
        assertThat(typeOf(BUBBLES)).isNull();
        assertThat(typeOf(KETCHUP)).isEqualTo("KETCHUP");
        assertThat(typeOf(HOT_KETCHUP)).isEqualTo("KETCHUP");
        assertThat(typeOf(KETCHUP_CRISPS)).isNull();

        var list = shoppingListService.create(new CreateShoppingListRequest("Test 18.10."), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        sapun
                        tečni sapun
                        kečap
                        ljuti kečap
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).extracting(StoreItemOffer::productName)
                .containsExactly(BAR_SOAP, LIQUID_SOAP, KETCHUP, HOT_KETCHUP);
    }

    /** The test of 18.10.2026 found a pizza and Whiskas before chicken. */
    @Test
    void aSearchForChickenFindsChickenBeforePizzaAndCatFood() {
        existingShop();

        for (String query : List.of("piletina", "piletina oko 1kg")) {
            var page = searchService.search(query, 0, 10, true, null, false);
            assertThat(page.items()).as(query).isNotEmpty();
            assertThat(page.items().get(0).name()).as(query).containsIgnoringCase("piletina cela");
        }

        // Asked for, cat food still comes first.
        var page = searchService.search("whiskas piletina", 0, 10, true, null, false);
        assertThat(page.items().get(0).name()).containsIgnoringCase("whiskas");
    }
}
