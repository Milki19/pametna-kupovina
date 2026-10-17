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
 * The third round, from the test of 17.10.2026: in Jedna stanica METRO sold
 * table margarine as "maslac" and chicken liver as "piletina", chicken was
 * the back everywhere, "šampon" a children's one, and "deterdžent za sudove",
 * "pasta za zube" and "čokolada" were not a kind step 1 knew (V134).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class UsualKindThirdRoundTest {

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

    private static final String TOKEN = "usual-kind-3";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String TABLE_MARGARINE = "DIJAMANT ST.MARG.MASLAC 500G";
    private static final String BUTTER = "MASLAC 82% 250G";
    private static final String CHICKEN_LIVER = "PIL. JETRA I SRCE 1KG";
    private static final String CHICKEN_BACK = "PILECA LEDJA OKO 1KG";
    private static final String DRUMSTICK = "PILECI BATAK 1KG";
    private static final String CHILDREN_SHAMPOO = "SAMPON DECIJI 300ML";
    private static final String SHAMPOO = "SAMPON ZA KOSU 400ML";
    private static final String DISHWASHER_TABLETS = "SOMAT TABLETE ZA MASINU ZA SUDOVE 30/1";
    private static final String DISHWASHER_SALT = "FINISH SO ZA MASINU ZA SUDOVE 1,5KG";
    private static final String DISH_DETERGENT = "FAIRY DETERDZENT ZA SUDOVE 450ML";
    private static final String TOOTHBRUSH = "CETKICA ZA ZUBE COLGATE";
    private static final String CHILDREN_TOOTHPASTE = "ELMEX DECIJA PASTA ZA ZUBE 50ML";
    private static final String TOOTHPASTE = "COLGATE PASTA ZA ZUBE 75ML";
    private static final String BISCUIT = "KEKS SA COKOLADOM 200G";
    private static final String CHOCOLATE_MILK = "MLEKO SA UKUSOM COKOLADE 0,5L";
    private static final String COOKING_CHOCOLATE = "COKOLADA ZA KUVANJE 200G";
    private static final String CHOCOLATE = "MILKA COKOLADA MLECNA 100G";

    private static final String MEAT = "8;Sveže i prerađeno meso";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + row(";", TABLE_MARGARINE, "199,99")
            + row(";", BUTTER, "399,99")
            + row(MEAT, CHICKEN_LIVER, "199,99")
            + row(MEAT, CHICKEN_BACK, "249,99")
            + row(MEAT, DRUMSTICK, "599,99")
            + row(";", CHILDREN_SHAMPOO, "199,99")
            + row(";", SHAMPOO, "349,99")
            + row(";", DISHWASHER_TABLETS, "99,99")
            + row(";", DISHWASHER_SALT, "89,99")
            + row(";", DISH_DETERGENT, "249,99")
            + row(";", TOOTHBRUSH, "99,99")
            + row(";", CHILDREN_TOOTHPASTE, "149,99")
            + row(";", TOOTHPASTE, "199,99")
            + row(";", BISCUIT, "99,99")
            + row(";", CHOCOLATE_MILK, "89,99")
            + row(";", COOKING_CHOCOLATE, "149,99")
            + row(";", CHOCOLATE, "189,99");

    private static int barcode = 3000;

    private static String row(String category, String name, String price) {
        barcode++;
        return category + ";" + name + ";Testna;86000000" + barcode + ";KOM"
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
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/round3.csv", exchange -> {
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
                        VALUES ('ROUND3', 'Round three', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/round3.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("ROUND3").status())
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
                        VALUES (?, ?, 'ROUND3-1', 'Round three objekat', 'Test adresa', 'Novi Sad', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
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

    @Test
    void stepOneKnowsDishDetergentToothpasteAndChocolate() {
        assertThat(intentResolver.resolve("deterdžent za sudove"))
                .get().extracting(ShoppingIntentResolver.ResolvedShoppingIntent::shoppingIntentCode)
                .isEqualTo("DISH_DETERGENT");
        assertThat(intentResolver.resolve("pasta za zube"))
                .get().extracting(ShoppingIntentResolver.ResolvedShoppingIntent::shoppingIntentCode)
                .isEqualTo("TOOTHPASTE");
        assertThat(intentResolver.resolve("čokolada"))
                .get().extracting(ShoppingIntentResolver.ResolvedShoppingIntent::shoppingIntentCode)
                .isEqualTo("CHOCOLATE");
    }

    @Test
    void theUsualKindWinsOverTheCheaperLookAlike() {
        long shop = shop();

        assertThat(typeOf(TABLE_MARGARINE)).isNull();
        assertThat(typeOf(BUTTER)).isEqualTo("BUTTER");
        assertThat(typeOf(DISH_DETERGENT)).isEqualTo("DISH_DETERGENT");
        assertThat(typeOf(DISHWASHER_SALT)).isNull();
        assertThat(typeOf(TOOTHPASTE)).isEqualTo("TOOTHPASTE");
        assertThat(typeOf(TOOTHBRUSH)).isNull();
        assertThat(typeOf(CHOCOLATE)).isEqualTo("CHOCOLATE");
        assertThat(typeOf(BISCUIT)).isNull();
        assertThat(typeOf(CHOCOLATE_MILK)).isNotEqualTo("CHOCOLATE");

        var list = shoppingListService.create(new CreateShoppingListRequest("Test 17.10."), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        maslac
                        piletina 1kg
                        šampon
                        deterdžent za sudove
                        pasta za zube
                        čokolada
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).extracting(StoreItemOffer::productName)
                .containsExactly(BUTTER, DRUMSTICK, SHAMPOO, DISH_DETERGENT, TOOTHPASTE, CHOCOLATE);
    }
}
