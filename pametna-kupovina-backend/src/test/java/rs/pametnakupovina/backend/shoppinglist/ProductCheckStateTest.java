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
import rs.pametnakupovina.backend.retailer.RetailerRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Korak 1 (14.10.): „Neka aplikacija izabere" za „BRAŠNO T-400 1 kg" vraćao
 * je grešku 400, a vrsta koju ne poznajemo bila je „nije pronađeno" u koraku
 * 1 iako ju je plan računao.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class ProductCheckStateTest {

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

    private static final String TOKEN = "product-check-state";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + """
            BRASNO;Brašno;Brasno T-500 Zitko 1kg;Zitko;;KOM;Test format;89,99;;13-09-2026;89,99;;;10
            RAZNO;Razno;Kvrcko zuti 100g;Kvrcko;;KOM;Test format;99,99;;13-09-2026;999,90;;;20
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
    private RetailerRepository retailerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/korak.csv", exchange -> {
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

    private void chainWithoutAddresses() {
        if (retailerRepository.findByCode("KORAK").isPresent()) {
            return;
        }
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('KORAK', 'Korak', ?)
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/korak.csv")
                .update();
        assertThat(priceImportService.importPrices("KORAK").status())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void anyOfTheKindDropsTheWordsItCannotRead() {
        chainWithoutAddresses();
        var list = shoppingListService.create(new CreateShoppingListRequest("Brašno"), callers.account(TOKEN));
        ShoppingListItemResponse flour = shoppingListService.addItem(
                list.id(),
                callers.account(TOKEN),
                new AddShoppingListItemRequest("BRAŠNO OSTRO 1 kg", "BRAŠNO OSTRO 1 kg", null,
                        BigDecimal.ONE, ShoppingItemRule.EXACT_PRODUCT)
        );

        // Izmena stavke i dalje traži da se vrsta upiše posebno.
        assertThatThrownBy(() -> shoppingListService.updateItem(
                list.id(), flour.id(), callers.account(TOKEN),
                new UpdateShoppingListItemRequest(flour.name(), flour.rawInput(), null, BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints(flour.name(), null, null, null, null))
        )).isInstanceOf(ResponseStatusException.class);

        ShoppingListItemResponse any = shoppingListService.useAnyOfTheKind(
                list.id(), flour.id(), callers.account(TOKEN));

        assertThat(any.name()).isEqualTo("BRAŠNO OSTRO 1 kg");
        assertThat(any.matchingRule()).isEqualTo(ShoppingItemRule.FLEXIBLE_CATEGORY);
        assertThat(any.matchingStatus()).isEqualTo(ShoppingItemMatchingStatus.CONFIRMED);
        assertThat(any.flexibleConstraints().requiredBaseUnit()).isEqualTo("g");
        assertThat(any.flexibleConstraints().targetQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    void anUnknownKindIsFoundExactlyWhenThePlanCanPriceIt() {
        chainWithoutAddresses();
        var list = shoppingListService.create(new CreateShoppingListRequest("Nepoznato"), callers.account(TOKEN));
        ShoppingListItemResponse known = shoppingListService.addItem(
                list.id(), callers.account(TOKEN),
                new AddShoppingListItemRequest("kvrcko", "kvrcko", null, BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints("kvrcko", null, null, null, null)));
        ShoppingListItemResponse unknown = shoppingListService.addItem(
                list.id(), callers.account(TOKEN),
                new AddShoppingListItemRequest("zvrcko", "zvrcko", null, BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints("zvrcko", null, null, null, null)));

        ShoppingListMatchingResponse result = matchingService.match(list.id(), callers.caller(TOKEN), true);

        assertThat(result.items())
                .filteredOn(item -> item.itemId().equals(known.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.matchingStatus())
                        .isEqualTo(ShoppingItemMatchingStatus.AUTO_MATCHED));
        assertThat(result.items())
                .filteredOn(item -> item.itemId().equals(unknown.id()))
                .singleElement()
                .satisfies(item -> assertThat(item.matchingStatus())
                        .isEqualTo(ShoppingItemMatchingStatus.UNMATCHED));
    }

    @Test
    void aChainWithoutShopAddressesSaysSo() {
        chainWithoutAddresses();
        assertThat(retailerRepository.findByCode("KORAK").orElseThrow().storeLocationsKnown())
                .isFalse();
    }
}
