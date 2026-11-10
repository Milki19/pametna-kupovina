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
 * "orasi" had no kind: it was looked up by the word in names, and once V160
 * left the nut mix out, the cheapest name saying "ORASI" was a baklava (S18
 * trial on a copy of the database, 10.11.2026). Nuts are a kind (V164).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class NutsAreAKindTest {

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

    private static final String TOKEN = "nuts-are-a-kind";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String WALNUTS = "ORAH JEZGRO 100G";
    private static final String ALMONDS = "BADEM 200G";
    private static final String NUT_MIX = "STUDENTSKA MESAVINA ORASI BADEM 200G";
    private static final String BAKLAVA = "BAKLAVA BADEM ORASI 42G GOLDEN FOOD";
    private static final String STRUDEL = "STRUDLA SA ORASIMA 400G";
    private static final String ALMOND_DRINK = "NAPITAK OD BADEMA ORASI 1L";
    private static final String CREAM = "SLATKA PAVLAKA 0.2L ORASI";
    private static final String SOAP = "SAPUN TECNI BADEM ORAH 500ML";
    private static final String CHOCOLATE = "COKOLADA MLECNA LESNIK 100G";
    private static final String COCONUT = "KOKOSOV ORAH KOM";
    // The first trial let every name with a nut word in: these became nuts.
    private static final String TURKISH_DELIGHT = "RATLUK ORAH 200G FINESSE";
    private static final String CARAMEL = "100G ARO KARAMELA-LESNIK";
    private static final String NUTMEG = "MUSKAT ORAH + RENDE 10G SPM";
    private static final String MILKA = "MILKA CEO LESNIK 100G";
    private static final String ALMOND_FLAKES = "BADEM LISTICI 100G";
    private static final String ROASTED_HAZELNUTS = "LESNIK PECENI 100GR";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7500;

    private static final String CATALOGUE = HEADER
            + row(WALNUTS, "399,99")
            + row(ALMONDS, "449,99")
            + row(NUT_MIX, "199,99")
            + row(BAKLAVA, "67,32")
            + row(STRUDEL, "249,99")
            + row(ALMOND_DRINK, "249,99")
            + row(CREAM, "129,99")
            + row(SOAP, "199,99")
            + row(CHOCOLATE, "149,99")
            + row(COCONUT, "149,99")
            + row(TURKISH_DELIGHT, "114,99")
            + row(CARAMEL, "45,00")
            + row(NUTMEG, "59,99")
            + row(MILKA, "199,99")
            + row(ALMOND_FLAKES, "209,99")
            + row(ROASTED_HAZELNUTS, "259,99");

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
        for (Map.Entry<String, String> list : Map.of("/nuts.csv", CATALOGUE).entrySet()) {
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
                .param("Nuts " + code)
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
                new CreateShoppingListRequest("Orasi"), callers.account(TOKEN));
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
    void walnutsAreWhatTheListSaysOrasi() {
        long shop = shop("NUTS", "/nuts.csv");
        var offers = offers(shop, "orasi\nbademi\nlešnici");
        String seen = seen(offers);

        // Cheaper, but a baklava, Turkish delight, a strudel and a nut mix are not walnuts.
        assertThat(productFor(offers, "orasi")).as(seen).isEqualTo(WALNUTS);
        // Almonds before almond flakes; a hazelnut caramel is not hazelnuts.
        assertThat(productFor(offers, "bademi")).as(seen).isEqualTo(ALMONDS);
        assertThat(productFor(offers, "le")).as(seen).isEqualTo(ROASTED_HAZELNUTS);
        assertThat(offers.values()).as(seen).noneMatch(StoreItemOffer::wantedMissing);
    }

    /** A cake, a drink, cream, soap, chocolate or a sweet with nuts is not nuts. */
    @Test
    void whatOnlySaysTheWordIsNotNuts() {
        shop("NUTS", "/nuts.csv");
        Map<String, String> kinds = jdbcClient.sql("""
                        SELECT product.name, COALESCE(type.code, '-') AS code
                        FROM app.retailer_product AS product
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                         AND retailer.code = 'NUTS'
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
                .containsEntry(WALNUTS, "NUTS")
                .containsEntry(ALMONDS, "NUTS")
                .containsEntry(ALMOND_FLAKES, "NUTS")
                .containsEntry(ROASTED_HAZELNUTS, "NUTS")
                .containsEntry(ALMOND_DRINK, "PLANT_DRINK");
        for (String other : List.of(BAKLAVA, STRUDEL, ALMOND_DRINK, CREAM, SOAP, CHOCOLATE, COCONUT,
                TURKISH_DELIGHT, CARAMEL, NUTMEG, MILKA)) {
            assertThat(kinds.get(other)).as(other + " " + kinds).isNotEqualTo("NUTS");
        }
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
