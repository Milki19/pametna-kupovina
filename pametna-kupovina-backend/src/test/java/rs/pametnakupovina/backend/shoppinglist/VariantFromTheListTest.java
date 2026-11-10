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
 * Šesti test, 6.11.2026: „Nespresso kapsule“ su postale mlevena kafa, „Coca
 * Cola zero“ obična Coca Cola, a „orasi“ mešavina sa suvim voćem. Varijanta
 * koju spisak kaže je filter (V160): radnja bez nje nema ponudu za tu
 * stavku, umesto da ponudi nešto drugo kao zamenu.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class VariantFromTheListTest {

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

    private static final String TOKEN = "variant-from-the-list";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String MESSAGE = """
            Nespresso kapsule
            Coca Cola zero
            orasi
            kafa u zrnu
            """;

    private static final String GROUND = "GRAND KAFA MLEVENA 200G";
    private static final String DOLCE_GUSTO = "NESCAFE DOLCE GUSTO KAFA KAPSULE 16KOM";
    // A line without a kind finds products whose first words say it.
    private static final String NESPRESSO = "STARBUCKS NESPRESSO KAFA KAPSULE 10KOM";
    private static final String BEANS = "LAVAZZA KAFA U ZRNU 1KG";
    private static final String COLA = "COCA COLA 1,5L";
    private static final String COLA_ZERO = "COCA COLA ZERO 1,5L";
    private static final String NUT_MIX = "STUDENTSKA MESAVINA ORASI SUVO GROZDJE 200G";
    private static final String WALNUTS = "ORASI JEZGRO 100G";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7100;

    /** A shop with only the other variant of each line. */
    private static final String OTHER_VARIANTS = HEADER
            + row(GROUND, "289,99")
            + row(DOLCE_GUSTO, "649,99")
            + row(COLA, "149,99")
            + row(NUT_MIX, "249,99");

    /** A shop with what the list says, and the other variants, cheaper. */
    private static final String ASKED_VARIANTS = HEADER
            + row(GROUND, "189,99")
            + row(NESPRESSO, "599,99")
            + row(BEANS, "1599,99")
            + row(COLA, "99,99")
            + row(COLA_ZERO, "159,99")
            + row(NUT_MIX, "149,99")
            + row(WALNUTS, "299,99");

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
                "/variant-other.csv", OTHER_VARIANTS,
                "/variant-asked.csv", ASKED_VARIANTS).entrySet()) {
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
                .param("Variant " + code)
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

    private long pasted(String text) {
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Šesti test"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(), callers.account(TOKEN), new PasteShoppingListItemsRequest(text));
        matchingService.match(list.id(), callers.caller(TOKEN), true);
        return list.id();
    }

    private Map<String, String> chosen(long listId, long shop) {
        return offerRepository.findPriceListOffers(listId, List.of(shop), PRICE_DATE).stream()
                .filter(StoreItemOffer::available)
                .collect(Collectors.toMap(
                        offer -> offer.requestedName().toLowerCase(),
                        StoreItemOffer::productName,
                        (a, b) -> a));
    }

    private String items(long listId) {
        return jdbcClient.sql("""
                        SELECT string_agg(name || ' | ' || matching_rule || ' | '
                               || COALESCE(flexible_category_normalized, '-'), E'\\n')
                        FROM app.shopping_list_item WHERE shopping_list_id = ?
                        """).param(listId).query(String.class).single();
    }

    @Test
    void theCatalogueKnowsEachVariant() {
        shop("VARIANT_ASKED", "/variant-asked.csv");

        Map<String, String> values = jdbcClient.sql("""
                        SELECT product.name || ' ' || definition.code AS what, assignment.text_value
                        FROM app.retailer_product_attribute AS assignment
                        JOIN app.retailer_product AS product ON product.id = assignment.retailer_product_id
                        JOIN app.product_attribute_definition AS definition
                          ON definition.id = assignment.attribute_definition_id
                        WHERE assignment.assignment_source = 'ATTRIBUTE_RULE'
                        """)
                .query((rs, row) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

        assertThat(values).as(values.toString())
                .containsEntry(NESPRESSO + " COFFEE_FORM", "CAPSULES")
                .containsEntry(NESPRESSO + " CAPSULE_SYSTEM", "NESPRESSO")
                .containsEntry(BEANS + " COFFEE_FORM", "BEANS")
                .containsEntry(GROUND + " COFFEE_FORM", "GROUND")
                .containsEntry(COLA_ZERO + " SUGAR_CONTENT", "ZERO")
                .containsEntry(COLA + " SUGAR_CONTENT", "REGULAR")
                .containsEntry(NUT_MIX + " NUT_MIX", "MIX")
                .doesNotContainKey(WALNUTS + " NUT_MIX");
    }

    @Test
    void aShopWithoutTheVariantOffersNothingElseForIt() {
        long shop = shop("VARIANT_OTHER", "/variant-other.csv");
        long listId = pasted(MESSAGE);

        Map<String, String> chosen = chosen(listId, shop);
        String seen = chosen + "\n" + items(listId);

        assertThat(chosen.values()).as(seen)
                .doesNotContain(GROUND, DOLCE_GUSTO, COLA, NUT_MIX);
    }

    @Test
    void theVariantTheListSaysIsChosenEvenWhereAnotherIsCheaper() {
        long shop = shop("VARIANT_ASKED", "/variant-asked.csv");
        long listId = pasted(MESSAGE);

        Map<String, String> chosen = chosen(listId, shop);
        String seen = chosen + "\n" + items(listId);

        assertThat(chosen).as(seen).hasSize(4);
        assertThat(chosen.get("nespresso kapsule")).as(seen).isEqualTo(NESPRESSO);
        assertThat(chosen.get("coca cola zero")).as(seen).isEqualTo(COLA_ZERO);
        assertThat(chosen.get("orasi")).as(seen).isEqualTo(WALNUTS);
        assertThat(chosen.get("kafa u zrnu")).as(seen).isEqualTo(BEANS);
    }

    /** Without a variant nothing changes: "kafa" is ground, "coca cola" the plain one. */
    @Test
    void aLineWithoutAVariantKeepsTheUsualKind() {
        long shop = shop("VARIANT_ASKED", "/variant-asked.csv");
        long listId = pasted("kafa\ncoca cola");

        Map<String, String> chosen = chosen(listId, shop);
        String seen = chosen + "\n" + items(listId);

        assertThat(chosen.get("kafa")).as(seen).isEqualTo(GROUND);
        assertThat(chosen.get("coca cola")).as(seen).isEqualTo(COLA);
    }
}
