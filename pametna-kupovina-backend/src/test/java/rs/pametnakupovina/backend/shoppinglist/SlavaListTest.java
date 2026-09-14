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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's slava list against one shop whose cheapest match for each word
 * is the wrong thing: olive oil, a protein ćevap, boiled sausage, dark beer
 * of the right brand, two spritzers for a litre of rosé and marinated wings.
 * Pasted as written, each line has to land on what a shopper means.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class SlavaListTest {

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

    private static final String TOKEN = "slava-list";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + """
            ULJE;Ulje;Ulje suncokretovo Maxi 1l;Maxi;;KOM;Test format;174,99;;13-09-2026;174,99;;;20
            ULJE;Ulje;Ulje maslinovo extra vergine WM 1l;WM;;KOM;Test format;1017,00;;13-09-2026;1017,00;;;20
            MESO;Meso;Proteinski cevap 500g;Maxi;;KOM;Test format;349,99;;13-09-2026;699,98;;;10
            MESO;Meso;Juneci cevap 500g;Maxi;;KOM;Test format;549,99;;13-09-2026;1099,98;;;10
            MESO;Meso;Kobasica pileca barena Maxi 500g;Maxi;;KOM;Test format;119,99;;13-09-2026;239,98;;;10
            MESO;Meso;Grill kobasica Carnex 280g;Carnex;;KOM;Test format;199,99;;13-09-2026;714,25;;;10
            MESO;Meso;Pileca marinirana krilca blaga 500g MAP;Maxi;;KOM;Test format;179,99;;13-09-2026;359,98;;;10
            MESO;Meso;Pileca krilca 1kg;Maxi;;KOM;Test format;389,99;;13-09-2026;389,99;;;10
            PIVO;Pivo;PIVO ZAJEČARSKO CRNO 0,5L PB;ZAJEČARSKO;;KOM;Test format;79,99;;13-09-2026;159,98;;;20
            PIVO;Pivo;Pivo svetlo Zajecarsko 0,5l RGB;Zajecarsko;;KOM;Test format;85,99;;13-09-2026;171,98;;;20
            PIVO;Pivo;Pivo Lav 0,5l;Lav;;KOM;Test format;69,99;;13-09-2026;139,98;;;20
            VINO;Vino;Vino ruzicasto Rose Rubin 1l;Rubin;;KOM;Test format;419,99;;13-09-2026;419,99;;;20
            VINO;Vino;VINO ROZE SPRICER RUBIN 0.5L;RUBIN;;KOM;Test format;149,99;;13-09-2026;299,98;;;20
            VINO;Vino;Vinjak Rubin 1l;Rubin;;KOM;Test format;999,99;;13-09-2026;999,99;;;20
            VODA;Voda;Mineralna voda gazirana Minaqua 2l;Minaqua;;KOM;Test format;69,99;;13-09-2026;35,00;;;20
            """;

    private static HttpServer csvServer;

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
        csvServer.createContext("/slava.csv", exchange -> {
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
                        VALUES ('SLAVA', 'Slava', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/slava.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("SLAVA").status())
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
                        VALUES (?, ?, 'SLAVA-1', 'Slava objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    @Test
    void everyLineLandsOnWhatTheShopperMeans() {
        long shop = shop();
        var list = shoppingListService.create(new CreateShoppingListRequest("Slava"), TOKEN);

        List<ShoppingListItemResponse> items = shoppingListService.addPastedItems(
                list.id(),
                TOKEN,
                new PasteShoppingListItemsRequest("""
                        Ulje
                        Ćevapi 1kg
                        Kobasice 1kg
                        Krilca 1kg
                        Pivo Zaječarsko 0.5
                        Rubin roze 1l
                        Kisela voda 1.75
                        """)
        ).items();

        assertThat(items).allSatisfy(item -> assertThat(item.matchingRule())
                .isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY));
        ShoppingListItemResponse beer = items.get(4);
        assertThat(beer.name()).isEqualTo("Pivo Zaječarsko");
        assertThat(beer.flexibleConstraints().requiredBrand()).isEqualTo("Zaječarsko");
        assertThat(beer.flexibleConstraints().targetQuantity()).isEqualByComparingTo("500");
        assertThat(items.get(6).flexibleConstraints().targetQuantity()).isEqualByComparingTo("1750");

        Map<String, String> chosen = offerRepository
                .findPriceListOffers(list.id(), List.of(shop), PRICE_DATE)
                .stream()
                .collect(Collectors.toMap(
                        StoreItemOffer::requestedName,
                        offer -> String.valueOf(offer.productName())
                ));

        assertThat(chosen).containsEntry("Ulje", "Ulje suncokretovo Maxi 1l")
                .containsEntry("Ćevapi", "Juneci cevap 500g")
                .containsEntry("Kobasice", "Grill kobasica Carnex 280g")
                .containsEntry("Krilca", "Pileca krilca 1kg")
                .containsEntry("Pivo Zaječarsko", "Pivo svetlo Zajecarsko 0,5l RGB")
                .containsEntry("Rubin roze", "Vino ruzicasto Rose Rubin 1l")
                .containsEntry("Kisela voda", "Mineralna voda gazirana Minaqua 2l");
    }

    @Test
    void aLinePastedBeforeTheWordsWereKnownIsReadAgain() {
        var list = shoppingListService.create(new CreateShoppingListRequest("Stari spisak"), TOKEN);
        // How "Kisela voda 1.75" was stored before a bare number was read.
        ShoppingListItemResponse old = shoppingListService.addItem(
                list.id(),
                TOKEN,
                new AddShoppingListItemRequest(
                        "Kisela voda 1.75",
                        "Kisela voda 1.75",
                        null,
                        BigDecimal.valueOf(2),
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );

        matchingService.match(list.id(), TOKEN, true);

        ShoppingListItemResponse reread = shoppingListService.findById(list.id(), TOKEN)
                .items()
                .stream()
                .filter(item -> item.id().equals(old.id()))
                .findFirst()
                .orElseThrow();
        assertThat(reread.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(reread.name()).isEqualTo("Kisela voda");
        assertThat(reread.quantity()).isEqualByComparingTo("2");
        assertThat(reread.flexibleConstraints().targetQuantity()).isEqualByComparingTo("1750");
    }
}
