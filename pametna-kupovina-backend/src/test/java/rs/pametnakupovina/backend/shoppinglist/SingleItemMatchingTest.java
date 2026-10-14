package rs.pametnakupovina.backend.shoppinglist;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.TestCallers;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A decision in step 1 is about one line. Matching that line alone gives the
 * same answer the whole list would give for it and leaves the other lines as
 * they were, so the client does not wait for the whole list again.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class SingleItemMatchingTest {

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

    private static final String TOKEN = "single-item";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + """
            SIR;Sir;Sir Gauda Imlek 400g;Imlek;8600000000017;KOM;Test format;459,99;;13-09-2026;1149,98;;;10
            SIR;Sir;Sir Gauda Imlek 250g;Imlek;8600000000024;KOM;Test format;319,99;;13-09-2026;1279,96;;;10
            SIR;Sir;Sir Gauda Dukat 400g;Dukat;8600000000031;KOM;Test format;479,99;;13-09-2026;1199,98;;;10
            ULJE;Ulje;Ulje suncokretovo Dijamant 1l;Dijamant;8600000000048;KOM;Test format;174,99;;13-09-2026;174,99;;;20
            """;

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
        csvServer.createContext("/single.csv", exchange -> {
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

    private void catalogue() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('SINGLE', 'Single', ?)
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/single.csv")
                .update();

        assertThat(priceImportService.importPrices("SINGLE").status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void aDecisionOnOneLineIsAnsweredForThatLineAlone() {
        catalogue();
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Jedna stavka"),
                callers.account(TOKEN)
        );
        ShoppingListItemResponse cheese = add(list.id(), "Sir Gauda");
        ShoppingListItemResponse oil = add(list.id(), "Ulje suncokretovo Dijamant 1l");

        ShoppingListMatchingResponse whole = matchingService.match(
                list.id(),
                callers.caller(TOKEN),
                true
        );
        ShoppingItemMatchResult before = whole.items().stream()
                .filter(item -> item.itemId().equals(cheese.id()))
                .findFirst()
                .orElseThrow();
        ShoppingItemMatchResult oilBefore = whole.items().stream()
                .filter(item -> item.itemId().equals(oil.id()))
                .findFirst()
                .orElseThrow();
        assertThat(before.candidates()).isNotEmpty();

        // Matching the line again on its own gives what the whole list gave.
        ShoppingItemMatchResult again = matchingService.matchOne(
                list.id(),
                cheese.id(),
                callers.caller(TOKEN),
                true
        );
        assertThat(again.matchingStatus()).isEqualTo(before.matchingStatus());
        assertThat(again.candidates()).extracting(c -> c.name())
                .containsExactlyElementsOf(
                        before.candidates().stream().map(c -> c.name()).toList()
                );

        var chosen = before.candidates().getFirst();
        matchingService.resolve(
                list.id(),
                cheese.id(),
                callers.caller(TOKEN),
                new ResolveShoppingItemMatchRequest(
                        ShoppingItemMatchAction.CONFIRM,
                        chosen.canonicalProductId(),
                        null,
                        chosen.canonicalProductId() == null
                                ? chosen.productFamilyId()
                                : null
                )
        );

        ShoppingItemMatchResult confirmed = matchingService.matchOne(
                list.id(),
                cheese.id(),
                callers.caller(TOKEN),
                true
        );
        assertThat(confirmed.itemId()).isEqualTo(cheese.id());
        assertThat(confirmed.matchingStatus())
                .isEqualTo(ShoppingItemMatchingStatus.CONFIRMED);
        assertThat(confirmed.blocksOptimization()).isFalse();

        // The other line keeps the decision it had.
        ShoppingListItemResponse oilAfter = shoppingListService
                .findById(list.id(), callers.account(TOKEN))
                .items()
                .stream()
                .filter(item -> item.id().equals(oil.id()))
                .findFirst()
                .orElseThrow();
        assertThat(oilAfter.matchingDecisionId()).isEqualTo(oilBefore.decisionId());
        assertThat(oilAfter.matchingStatus()).isEqualTo(oilBefore.matchingStatus());
    }

    @Test
    void someoneElsesLineIsNotMatched() {
        var list = shoppingListService.create(
                new CreateShoppingListRequest("Tuđi spisak"),
                callers.account(TOKEN)
        );
        ShoppingListItemResponse item = add(list.id(), "Sir Gauda");

        assertThatThrownBy(() -> matchingService.matchOne(
                list.id(),
                item.id(),
                callers.caller("single-item-stranger"),
                true
        )).isInstanceOf(ResponseStatusException.class);

        var other = shoppingListService.create(
                new CreateShoppingListRequest("Drugi spisak"),
                callers.account(TOKEN)
        );
        assertThatThrownBy(() -> matchingService.matchOne(
                other.id(),
                item.id(),
                callers.caller(TOKEN),
                true
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Stavka nije pronađena");
    }

    private ShoppingListItemResponse add(Long listId, String name) {
        return shoppingListService.addItem(
                listId,
                callers.account(TOKEN),
                new AddShoppingListItemRequest(
                        name,
                        name,
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT
                )
        );
    }
}
