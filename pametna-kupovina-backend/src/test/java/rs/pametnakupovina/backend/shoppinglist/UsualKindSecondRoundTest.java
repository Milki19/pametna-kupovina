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
 * The second round from the test of 14.10.2026 in the evening: All Caffe is
 * not coffee, a softener is not laundry powder, one roll or wet wipes are not
 * a pack of toilet paper, "sir" is not urda, "maslac" is not margarine, and
 * "piletina 1kg" is chicken, not dog food (V126).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class UsualKindSecondRoundTest {

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

    private static final String TOKEN = "usual-kind-2";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String ALL_CAFFE = "KAFA CAR 100G ALL CAFFE";
    private static final String GROUND_COFFEE = "Grand kafa mlevena 200g";
    private static final String SOFTENER = "OM DUEL SUNKISSED 1.6L";
    private static final String LIQUID_DETERGENT = "Merix 2l";
    private static final String POWDER = "Duel prasak za ves 3kg";
    private static final String LENOR_SCENTED = "ARIEL TOUCH OF LENOR 70PRANJA 5,25KG";
    private static final String SILAN_SCENTED = "DET PERSIL POW EXP SILAN 72WL 5.4KG";
    private static final String ORNEL_DETERGENT = "DETERDŽENT ZA VEŠ CALMING ORNEL 4KG";
    private static final String ONE_ROLL = "Toalet papir 1/1";
    private static final String WET_PAPER = "TOALET PAPIR VL KIDS 60/1";
    private static final String TEN_ROLLS = "Toalet papir Perfex 10/1";
    private static final String URDA = "Sir urda 500g";
    private static final String GOUDA = "Sir gauda 500g";
    private static final String MARGARINE = "MASLAC BILJNI 250G";
    private static final String BUTTER = "Maslac 82% 250g";
    private static final String DOG_FOOD = "Moksi piletina 1kg";
    private static final String CHICKEN = "Pileci file 1kg";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + row(ALL_CAFFE, "99,99")
            + row(GROUND_COFFEE, "459,99")
            + row(SOFTENER, "299,99")
            + row(LIQUID_DETERGENT, "499,99")
            + row(POWDER, "899,99")
            + row(LENOR_SCENTED, "1999,99")
            + row(SILAN_SCENTED, "1899,99")
            + row(ORNEL_DETERGENT, "1199,99")
            + row(ONE_ROLL, "39,99")
            + row(WET_PAPER, "129,99")
            + row(TEN_ROLLS, "349,99")
            + row(URDA, "299,99")
            + row(GOUDA, "599,99")
            + row(MARGARINE, "99,99")
            + row(BUTTER, "399,99")
            + row(DOG_FOOD, "499,99")
            + row(CHICKEN, "899,99");

    private static int barcode = 2000;

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
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private CanonicalProductSearchService searchService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/round2.csv", exchange -> {
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
                        VALUES ('ROUND2', 'Round two', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/round2.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("ROUND2").status())
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
                        VALUES (?, ?, 'ROUND2-1', 'Round two objekat', 'Test adresa', 'Beograd', TRUE)
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
    void theUsualKindWinsOverTheCheaperLookAlike() {
        long shop = shop();

        assertThat(typeOf(ALL_CAFFE)).isNull();
        assertThat(typeOf(SOFTENER)).isNull();
        assertThat(typeOf(WET_PAPER)).isNull();
        assertThat(typeOf(MARGARINE)).isNull();
        assertThat(typeOf(POWDER)).isEqualTo("LAUNDRY_DETERGENT");
        // Powder that smells of a softener is still powder.
        assertThat(typeOf(LENOR_SCENTED)).isEqualTo("LAUNDRY_DETERGENT");
        assertThat(typeOf(SILAN_SCENTED)).isEqualTo("LAUNDRY_DETERGENT");
        assertThat(typeOf(ORNEL_DETERGENT)).isEqualTo("LAUNDRY_DETERGENT");
        assertThat(typeOf(TEN_ROLLS)).isEqualTo("TOILET_PAPER");
        assertThat(typeOf(BUTTER)).isEqualTo("BUTTER");
        assertThat(typeOf(DOG_FOOD)).isNull();

        var list = shoppingListService.create(new CreateShoppingListRequest("Test 14.10. uveče"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        kafa 200 g
                        prašak za veš
                        toalet papir
                        sir 500 g
                        maslac
                        piletina 1kg
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).extracting(StoreItemOffer::productName)
                .containsExactly(GROUND_COFFEE, POWDER, TEN_ROLLS, GOUDA, BUTTER, CHICKEN);
    }

    @Test
    void aSearchForChickenFindsChickenFirst() {
        jdbcClient.sql("SELECT id FROM app.store WHERE external_code = 'ROUND2-1'")
                .query(Long.class)
                .optional()
                .orElseGet(this::shop);

        var page = searchService.search("piletina 1kg", 0, 10, true, null, false);
        assertThat(page.items()).isNotEmpty();
        assertThat(page.items().get(0).name()).containsIgnoringCase("pile");
    }
}
