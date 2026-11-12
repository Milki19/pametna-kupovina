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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan's prices worked out once after an import (V172) give the same
 * offers as working them out on every plan, a sale and an old price from a
 * shop's own report included. A price that changes after the build makes the
 * tables stale, and the plan works its prices out itself until they are
 * built again.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PlanFactsTest {

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

    private static final String TOKEN = "plan-facts";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static final String PRICE_LIST = HEADER
            + "3;Sveže voće i povrće;JABUKA GLOSTER;;;KG;Test format;119,99;;13-09-2026;119,99;;;10\n"
            + "3;Sveže voće i povrće;JABUKA CRVENA 2KG;Testna;8600000002011;KOM;Test format;249,99;199,99;13-09-2026;100,00;10-09-2026;20-09-2026;10\n"
            + "3;Sveže voće i povrće;MANGO KOMAD;;;kg;Test format;219,99;;13-09-2026;628,54;;;10\n"
            + "8;Sveže i prerađeno meso;ĆEVAPI SA PULTA;;;KG;Test format;699,99;649,99;13-09-2026;649,99;01-09-2026;12-09-2026;10\n"
            + "3;Sveže voće i povrće;PARADAJZ;;;KG;Test format;189,99;;13-09-2026;189,99;;;10\n"
            + "3;Sveže voće i povrće;PARADAJZ MIKS 500G;Testna;8600000002028;KOM;Test format;199,99;;13-09-2026;399,98;;;10\n";

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
    private PlanFacts planFacts;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/facts.csv", exchange -> {
            byte[] body = PRICE_LIST.getBytes(StandardCharsets.UTF_8);
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
                        VALUES ('FACTS', 'Facts', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/facts.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("FACTS").status())
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
                        VALUES (?, ?, 'FACTS-1', 'Facts objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    /** A price the shop itself reported, which no current offer of the shop replaces. */
    private void ownOldPrice(long shop, String productName, String price) {
        jdbcClient.sql("""
                        INSERT INTO app.price_observation (
                            retailer_product_id, import_run_id, store_id, price_date, regular_price
                        )
                        SELECT product.id,
                               (SELECT MAX(run.id) FROM app.import_run AS run),
                               ?, ?, ?::NUMERIC
                        FROM app.retailer_product AS product
                        WHERE product.name = ?
                        """)
                .param(shop)
                .param(PRICE_DATE)
                .param(price)
                .param(productName)
                .update();
    }

    private List<StoreItemOffer> offers(long listId, long shop) {
        return offerRepository.findPriceListOffers(listId, List.of(shop), PRICE_DATE);
    }

    /** "jabuke" with no amount: a kilogram of the loose apples (V137). */
    private String looseApples(List<StoreItemOffer> offers) {
        assertThat(offers.get(1).productName()).isEqualTo("JABUKA GLOSTER");
        return offers.get(1).effectivePrice().toPlainString();
    }

    @Test
    void theTablesGiveTheSameOffersAndGoStaleWithTheNextPrice() {
        long shop = shop();
        ownOldPrice(shop, "PARADAJZ", "149.99");

        var list = shoppingListService.create(new CreateShoppingListRequest("Pijaca"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        jabuke 2kg
                        jabuke
                        ćevapi 1,5kg
                        mango 1kg
                        paradajz
                        paradajz 1kg
                        """)
        );

        assertThat(planFacts.freshFor(PRICE_DATE)).isFalse();
        List<StoreItemOffer> worked = offers(list.id(), shop);
        assertThat(worked).hasSize(6);
        assertThat(looseApples(worked)).isEqualTo("119.99");
        // The shop's own old price of tomatoes counts, in both ways.
        assertThat(worked.get(4).effectivePrice()).isEqualByComparingTo("149.99");

        PlanFacts.Build built = planFacts.build(PRICE_DATE);
        assertThat(built.asOfDate()).isEqualTo(PRICE_DATE);
        assertThat(built.looseProducts()).isEqualTo(1);
        assertThat(planFacts.freshFor(PRICE_DATE)).isTrue();
        assertThat(planFacts.freshFor(PRICE_DATE.plusDays(1))).isFalse();
        assertThat(offers(list.id(), shop)).isEqualTo(worked);

        // A price changed after the build, here by hand as a fix on the
        // server would be: the tables are stale, and the plan reads the new
        // price itself until they are built again.
        jdbcClient.sql("""
                        UPDATE app.current_price_offer AS offer
                        SET regular_price = 139.99, unit_price = 139.99
                        FROM app.retailer_product AS product
                        WHERE product.id = offer.retailer_product_id
                          AND product.name = 'JABUKA GLOSTER'
                        """)
                .update();
        assertThat(planFacts.freshFor(PRICE_DATE)).isFalse();
        List<StoreItemOffer> changed = offers(list.id(), shop);
        assertThat(looseApples(changed)).isEqualTo("139.99");

        planFacts.build(PRICE_DATE);
        assertThat(planFacts.freshFor(PRICE_DATE)).isTrue();
        assertThat(offers(list.id(), shop)).isEqualTo(changed);

        // A change that is undone leaves them fresh.
        jdbcClient.sql("""
                        DO $$
                        BEGIN
                            UPDATE app.price_observation SET regular_price = regular_price;
                            RAISE EXCEPTION 'undo';
                        EXCEPTION WHEN raise_exception THEN
                            NULL;
                        END $$
                        """)
                .update();
        assertThat(planFacts.freshFor(PRICE_DATE)).isTrue();
    }

    @Test
    void thePlanFromTheTablesDiffersOnlyWhereItReadsThem() {
        String live = StoreShoppingOfferRepository.OFFERS_SQL;
        String facts = StoreShoppingOfferRepository.OFFERS_FROM_FACTS_SQL;
        assertThat(live).contains("FROM app.price_observation AS loose", "-- [prices from here]");
        assertThat(facts)
                .contains("FROM app.plan_loose_product AS loose", "FROM app.plan_price_source AS own_price")
                .doesNotContain("app.price_observation AS loose", "app.price_observation AS observation",
                        "app.current_price_offer AS current_offer", "[prices", "[old prices");
        // Everything after the prices, every rank and condition, is the same text.
        String tail = live.substring(live.indexOf("-- [prices to here]") + "-- [prices to here]".length());
        assertThat(facts).endsWith(tail);
    }
}
