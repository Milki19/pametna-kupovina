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
 * "kokosov orah" took coconut pieces or grated coconut before the whole
 * fruit: the list word asked only for "kokos" among fruit, and a 100 g bag
 * costs less than a coconut (known after the sixth test, 11.11.2026).
 * Processed coconut is the unusual choice for that word (V167).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class CoconutIsTheWholeFruitTest {

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

    private static final String TOKEN = "coconut-is-the-whole-fruit";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String COCONUT = "KOKOSOV ORAH KOM";
    // Cheaper, but not a whole coconut.
    private static final String GRATED = "KOKOS RENDANI 100G";
    private static final String PIECES = "KOKOS KOMADICI 150G";
    private static final String CHIPS = "KOKOS CIPS 40G";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7700;

    private static final String CATALOGUE = HEADER
            + row(COCONUT, "189,99")
            + row(GRATED, "89,99")
            + row(PIECES, "119,99")
            + row(CHIPS, "99,99");

    // In the fruit section, as chains list them: the fruit rule (V87) reads
    // the category, and grated coconut and pieces were fruit there too.
    private static String row(String name, String price) {
        barcode++;
        return "3;Sveže voće i povrće;" + name + ";Testna;86000000" + barcode + ";KOM"
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
        for (Map.Entry<String, String> list : Map.of("/coconut.csv", CATALOGUE).entrySet()) {
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
                .param("Coconut " + code)
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
                new CreateShoppingListRequest("Kokos"), callers.account(TOKEN));
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
    void kokosovOrahIsTheWholeCoconut() {
        long shop = shop("COCONUT", "/coconut.csv");
        var offers = offers(shop, "kokosov orah");
        String seen = seen(offers);

        assertThat(productFor(offers, "kokos")).as(seen).isEqualTo(COCONUT);
        assertThat(offers.values()).as(seen).noneMatch(StoreItemOffer::wantedMissing);
    }

    /** "kokosov orah 2" is two coconuts, not two kilograms or two bags. */
    @Test
    void twoCoconutsAreTwoWholeOnes() {
        long shop = shop("COCONUT", "/coconut.csv");
        var offers = offers(shop, "kokosov orah 2");
        String seen = seen(offers);

        assertThat(productFor(offers, "kokos")).as(seen).isEqualTo(COCONUT);
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
