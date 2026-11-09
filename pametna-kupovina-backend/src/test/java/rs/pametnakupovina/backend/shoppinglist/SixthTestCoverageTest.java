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
import rs.pametnakupovina.backend.TestCallers;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sixth test (6.11.2026, item 9): "zobeno mleko", "kesice za
 * zamrzivač", "vanilin šećer" and "margarin za kolače" were not found, or
 * had no price, because the shops name them otherwise (V162).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class SixthTestCoverageTest {

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

    private static final String TOKEN = "sixth-test-coverage";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String OAT_DRINK = "NAPITAK OVAS 1L ALPRO";
    private static final String OAT_PORRIDGE = "OVSENA KASA BOOM BOX SUMSKO VOCE BADEM 60G";
    private static final String OAT_FLAKES = "OVSENE PAHULJICE 500G";
    private static final String ALMOND_DRINK = "NAPITAK OD BADEMA UHT 1L JOYA";
    private static final String MILK = "MLEKO 2,8% 1L";
    private static final String FREEZER_BAGS = "KESE ZA ZAMRZIVAC ZIPER FINO 1L 20/1";
    private static final String BIN_BAGS = "KESE ZA SMECE FINO 60L 10/1";
    private static final String ICE_BAGS = "KESE ZA LED 10/1";
    private static final String VANILLA_SUGAR = "VANILIN SECER 10G DR.OETKER";
    private static final String VANILLA_PUDDING = "PUDING VANILA 450G";
    private static final String SUGAR = "SECER KRISTAL 1KG";
    private static final String BAKING_MARGARINE = "MARGARIN ZA KOLACE VITA 250G";
    private static final String TABLE_MARGARINE = "MARGARIN DIJAMANT CLASSIC STONI 250G";
    private static final String PUFF_PASTRY = "LISNATO TESTO SA MARGARINOM 500G";
    private static final String BUTTER = "MASLAC 82% 250G";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7000;

    private static final String CATALOGUE = HEADER
            + row(OAT_DRINK, "299,99")
            + row(OAT_PORRIDGE, "89,99")
            + row(OAT_FLAKES, "149,99")
            + row(ALMOND_DRINK, "319,99")
            + row(MILK, "149,99")
            + row(FREEZER_BAGS, "189,99")
            + row(BIN_BAGS, "149,99")
            + row(ICE_BAGS, "99,99")
            + row(VANILLA_SUGAR, "19,99")
            + row(VANILLA_PUDDING, "159,99")
            + row(SUGAR, "119,99")
            + row(BAKING_MARGARINE, "179,99")
            + row(TABLE_MARGARINE, "169,99")
            + row(PUFF_PASTRY, "229,99")
            + row(BUTTER, "399,99");

    /** A shop that sells only the table margarine. */
    private static final String SMALL_CATALOGUE = HEADER
            + row(TABLE_MARGARINE, "159,99")
            + row(BUTTER, "389,99");

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
    private ShoppingListMatchingService matchingService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        for (Map.Entry<String, String> list : Map.of(
                "/sixth.csv", CATALOGUE,
                "/sixth-small.csv", SMALL_CATALOGUE).entrySet()) {
            csvServer.createContext(list.getKey(), exchange -> {
                byte[] body = list.getValue().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
        }
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private long shop(String code, String path) {
        return jdbcClient.sql("SELECT id FROM app.store WHERE external_code = ?")
                .param(code + "-1")
                .query(Long.class)
                .optional()
                .orElseGet(() -> newShop(code, path));
    }

    private long newShop(String code, String path) {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param("Sixth " + code)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + path)
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices(code).status())
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
                        VALUES (?, ?, ?, 'Objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .param(code + "-1")
                .query(Long.class)
                .single();
    }

    private Map<String, StoreItemOffer> offers(long shop, String text) {
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Šesti test"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(), callers.account(TOKEN), new PasteShoppingListItemsRequest(text));
        matchingService.match(list.id(), callers.caller(TOKEN), true);
        return offerRepository.findPriceListOffers(list.id(), List.of(shop), PRICE_DATE)
                .stream()
                .collect(Collectors.toMap(StoreItemOffer::requestedName, offer -> offer, (a, b) -> a));
    }

    private static String seen(Map<String, StoreItemOffer> offers) {
        return offers.values().stream()
                .map(offer -> offer.requestedName() + " -> " + offer.productName()
                        + (offer.wantedMissing() ? " (zamena)" : ""))
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    @Test
    void whatTheShopsNameOtherwiseIsFound() {
        long shop = shop("SIXTH", "/sixth.csv");
        var offers = offers(shop, "zobeno mleko\nkesice za zamrzivač\nvanilin šećer\nmargarin za kolače"
                + "\nbademovo mleko\nmleko\nšećer\nmaslac");
        String seen = seen(offers);

        assertThat(productFor(offers, "zobeno")).as(seen).isEqualTo(OAT_DRINK);
        assertThat(productFor(offers, "zamrziv")).as(seen).isEqualTo(FREEZER_BAGS);
        assertThat(productFor(offers, "vanilin")).as(seen).isEqualTo(VANILLA_SUGAR);
        assertThat(productFor(offers, "margarin")).as(seen).isEqualTo(BAKING_MARGARINE);
        assertThat(productFor(offers, "bademovo")).as(seen).isEqualTo(ALMOND_DRINK);
        // The plain words stay what they were.
        assertThat(offerFor(offers, "mleko", "zobeno", "bademovo").productName()).as(seen).isEqualTo(MILK);
        assertThat(offerFor(offers, "šećer", "vanilin").productName()).as(seen).isEqualTo(SUGAR);
        assertThat(productFor(offers, "maslac")).as(seen).isEqualTo(BUTTER);
        assertThat(offers.values()).as(seen).noneMatch(StoreItemOffer::wantedMissing);
    }

    /** Porridge, bin bags, pudding and pastry say the word but are not the kind. */
    @Test
    void whatOnlySaysTheWordIsNotTheKind() {
        shop("SIXTH", "/sixth.csv");
        Map<String, String> kinds = jdbcClient.sql("""
                        SELECT product.name, COALESCE(type.code, '-') AS code
                        FROM app.retailer_product AS product
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                         AND retailer.code = 'SIXTH'
                        LEFT JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        LEFT JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        """)
                .query((resultSet, row) -> Map.entry(resultSet.getString("name"), resultSet.getString("code")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        assertThat(kinds).as(kinds.toString())
                .containsEntry(OAT_DRINK, "PLANT_DRINK")
                .containsEntry(ALMOND_DRINK, "PLANT_DRINK")
                .containsEntry(FREEZER_BAGS, "FREEZER_BAG")
                .containsEntry(VANILLA_SUGAR, "VANILLA_SUGAR")
                .containsEntry(BAKING_MARGARINE, "MARGARINE")
                .containsEntry(TABLE_MARGARINE, "MARGARINE");
        for (String other : List.of(OAT_PORRIDGE, OAT_FLAKES, BIN_BAGS, ICE_BAGS, VANILLA_PUDDING, PUFF_PASTRY)) {
            assertThat(kinds.get(other)).as(other + " " + kinds)
                    .isNotIn("PLANT_DRINK", "FREEZER_BAG", "VANILLA_SUGAR", "MARGARINE");
        }
    }

    /** Margarine for cakes is a block; a table margarine only when the shop has no other. */
    @Test
    void aTableMargarineIsTakenOnlyWhenThereIsNoOther() {
        long small = shop("SIXTHB", "/sixth-small.csv");
        var offers = offers(small, "margarin za kolače");

        assertThat(productFor(offers, "margarin")).as(seen(offers)).isEqualTo(TABLE_MARGARINE);
    }

    private static StoreItemOffer offerFor(Map<String, StoreItemOffer> offers, String word, String... not) {
        return offers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase().contains(word))
                .filter(entry -> java.util.Arrays.stream(not)
                        .noneMatch(other -> entry.getKey().toLowerCase().contains(other)))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Nema stavke sa „" + word + "“: " + offers.keySet()));
    }

    private static String productFor(Map<String, StoreItemOffer> offers, String word) {
        return offerFor(offers, word).productName();
    }
}
