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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Apples and ćevapi from the counter have no size: the chain prices them by
 * the kilogram. With an amount on the list each kilogram counts as a pack, so
 * "jabuke 2kg" can be two kilograms of loose apples. A piece the chain also
 * writes in kilograms is not: its price list gives another price per
 * kilogram, or its name says "komad".
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class LooseGoodsByTheKilogramTest {

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

    private static final String TOKEN = "loose-goods";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String LOOSE_APPLES = "JABUKA GLOSTER";
    private static final String APPLE_BAG = "JABUKA CRVENA 2KG";
    private static final String ONE_APPLE = "JABUKA ZLATNI DELIŠES KOMAD";
    private static final String MANGO = "MANGO KOMAD";
    private static final String CEVAPI = "ĆEVAPI SA PULTA";
    private static final String TURKEY_MINCE = "MASA OD MLEV.CURECEG MESA400G";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + "3;Sveže voće i povrće;" + LOOSE_APPLES + ";;;KG;Test format;119,99;;13-09-2026;119,99;;;10\n"
            + "3;Sveže voće i povrće;" + APPLE_BAG + ";Testna;8600000002011;KOM;Test format;249,99;;13-09-2026;125,00;;;10\n"
            + "3;Sveže voće i povrće;" + ONE_APPLE + ";;;kg;Test format;19,99;;13-09-2026;19,99;;;10\n"
            + "3;Sveže voće i povrće;" + MANGO + ";;;kg;Test format;219,99;;13-09-2026;628,54;;;10\n"
            + "8;Sveže i prerađeno meso;" + CEVAPI + ";;;KG;Test format;699,99;;13-09-2026;699,99;;;10\n"
            + "8;Sveže i prerađeno meso;" + TURKEY_MINCE + ";;;kg;Test format;399,99;;13-09-2026;399,99;;;10\n";

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/loose.csv", exchange -> {
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
                        VALUES ('LOOSE', 'Loose', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/loose.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("LOOSE").status())
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
                        VALUES (?, ?, 'LOOSE-1', 'Loose objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    private boolean soldByTheKilogram(String productName) {
        return jdbcClient.sql("""
                        SELECT app.sold_by_the_kilogram(id)
                        FROM app.retailer_product
                        WHERE name = ?
                        """)
                .param(productName)
                .query(Boolean.class)
                .single();
    }

    @Test
    void anAmountOfLooseGoodsCountsTheirKilograms() {
        long shop = shop();

        assertThat(soldByTheKilogram(LOOSE_APPLES)).isTrue();
        assertThat(soldByTheKilogram(CEVAPI)).isTrue();
        // A bag has its size, a piece says so, a mango's price per kilogram is
        // not its price, and "mesa400g" is a 400 g pack the name reader missed.
        assertThat(soldByTheKilogram(APPLE_BAG)).isFalse();
        assertThat(soldByTheKilogram(ONE_APPLE)).isFalse();
        assertThat(soldByTheKilogram(MANGO)).isFalse();
        assertThat(soldByTheKilogram(TURKEY_MINCE)).isFalse();

        var list = shoppingListService.create(new CreateShoppingListRequest("Pijaca"), TOKEN);
        shoppingListService.addPastedItems(
                list.id(),
                TOKEN,
                new PasteShoppingListItemsRequest("""
                        jabuke 2kg
                        ćevapi 3kg
                        mango 1kg
                        ćevapi 1,5kg
                        jabuke
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).hasSize(5);

        // Two kilograms of loose apples beat the 2 kg bag; one apple for 19,99
        // is not a kilogram.
        assertThat(offers.get(0)).satisfies(apples -> {
            assertThat(apples.productName()).isEqualTo(LOOSE_APPLES);
            assertThat(apples.purchaseQuantity().packages()).isEqualByComparingTo("2");
            assertThat(apples.purchaseQuantity().packageSize()).isEqualByComparingTo("1000");
            assertThat(apples.purchaseQuantity().baseUnit()).isEqualTo("g");
            assertThat(apples.purchaseQuantity().unitPrice()).isEqualByComparingTo("119.99");
            assertThat(apples.lineTotal()).isEqualByComparingTo("239.98");
        });
        assertThat(offers.get(1)).satisfies(cevapi -> {
            assertThat(cevapi.productName()).isEqualTo(CEVAPI);
            assertThat(cevapi.purchaseQuantity().packages()).isEqualByComparingTo("3");
            assertThat(cevapi.lineTotal()).isEqualByComparingTo("2099.97");
        });
        assertThat(offers.get(2).available()).isFalse();
        // Whole kilograms only: two would be a third more than 1,5 kg.
        assertThat(offers.get(3).available()).isFalse();
        // Without an amount nothing changes: the bag with a size comes first.
        assertThat(offers.get(4).productName()).isEqualTo(APPLE_BAG);
    }
}
