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
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 32 of 100 pasted words (med, senf, majonez, keks) were marked "Nije
 * prepoznato" until "Izračunaj" found them, and the grandmother's baking
 * list had 8 of 14 marked although the check found 13 (šesti test,
 * 6.11.2026). A pasted line is now checked before the answer goes back, as
 * the check would, so only what it finds nothing for comes back unmatched.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PastedLinesAreCheckedTest {

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

    private static final String TOKEN = "pasted-lines-are-checked";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7000;

    private static final String CATALOGUE = HEADER
            + row("MED LIVADSKI 900G", "899,99")
            + row("SENF BLAGI 200G", "119,99")
            + row("MAJONEZ 300G", "189,99")
            + row("MLEKO 2,8% 1L", "149,99");

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
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/pasted.csv", exchange -> {
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

    @Test
    void onlyWhatTheCheckCannotFindComesBackUnmatched() {
        jdbcClient.sql("INSERT INTO app.retailer (code, name, dataset_url) VALUES ('PASTESHOP', 'Lepljenje', ?)")
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/pasted.csv")
                .update();
        assertThat(priceImportService.importPrices("PASTESHOP").status()).isEqualTo("SUCCEEDED");

        var caller = callers.caller(TOKEN);
        var list = shoppingListService.create(new CreateShoppingListRequest("Kolači"), caller.accountId());
        var pasted = matchingService.checkPasted(
                list.id(),
                caller,
                shoppingListService.addPastedItems(
                        list.id(),
                        caller.accountId(),
                        new PasteShoppingListItemsRequest("med\nsenf\nmajonez\nmleko\nfrobnikator plavi\n")
                )
        );

        Map<String, ShoppingItemMatchingStatus> answered = pasted.items().stream()
                .collect(Collectors.toMap(ShoppingListItemResponse::name, ShoppingListItemResponse::matchingStatus));
        assertThat(answered).as("%s", answered).hasSize(5);
        assertThat(answered.get("frobnikator plavi")).as("%s", answered)
                .isEqualTo(ShoppingItemMatchingStatus.UNMATCHED);
        assertThat(answered.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("frobnikator plavi"))
                .map(Map.Entry::getValue))
                .as("%s", answered)
                .doesNotContain(ShoppingItemMatchingStatus.PENDING, ShoppingItemMatchingStatus.UNMATCHED);

        // What the paste answered is what the list keeps and what "Izračunaj" finds.
        Map<String, ShoppingItemMatchingStatus> stored = shoppingListService.findById(list.id(), caller.accountId())
                .items().stream()
                .collect(Collectors.toMap(ShoppingListItemResponse::name, ShoppingListItemResponse::matchingStatus));
        assertThat(stored).isEqualTo(answered);
        Map<String, ShoppingItemMatchingStatus> checkedLater = matchingService.match(list.id(), caller, true)
                .items().stream()
                .collect(Collectors.toMap(ShoppingItemMatchResult::requestedName, ShoppingItemMatchResult::matchingStatus));
        assertThat(checkedLater.get("frobnikator plavi")).isEqualTo(ShoppingItemMatchingStatus.UNMATCHED);
        answered.forEach((name, status) -> {
            if (status != ShoppingItemMatchingStatus.UNMATCHED) {
                assertThat(checkedLater.get(name)).as(name).isNotEqualTo(ShoppingItemMatchingStatus.UNMATCHED);
            }
        });
    }

    @Test
    void aListTheWordsAlreadyNameIsNotLookedUpAgain() {
        var caller = callers.caller(TOKEN + "-known");
        var list = shoppingListService.create(new CreateShoppingListRequest("Mleko"), caller.accountId());
        var added = shoppingListService.addPastedItems(
                list.id(),
                caller.accountId(),
                new PasteShoppingListItemsRequest("mleko\n")
        );

        assertThat(matchingService.checkPasted(list.id(), caller, added)).isSameAs(added);
    }
}
