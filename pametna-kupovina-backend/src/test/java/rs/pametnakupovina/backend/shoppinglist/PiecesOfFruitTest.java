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
import rs.pametnakupovina.backend.TestCallers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fruit and vegetables counted are pieces (šesti test, 6.11.2026): "limun
 * 3" was 3 kg for 389,97 RSD and "avokado 2" 1,5 kg. Three lemons are three
 * pieces where the shop sells them by the piece, and 3 × 100 g weighed out or
 * in packs elsewhere (V156).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PiecesOfFruitTest {

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

    private static final String TOKEN = "pieces-of-fruit";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String LOOSE_LEMONS = "LIMUN";
    private static final String LEMON_NET = "LIMUN MREZA 500G";
    private static final String AVOCADO = "AVOKADO KOM";
    private static final String LOOSE_AVOCADO = "AVOKADO";
    private static final String LOOSE_APPLES = "JABUKA GLOSTER";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + "3;Sveže voće i povrće;" + LOOSE_LEMONS + ";;;KG;Test format;129,99;;13-09-2026;129,99;;;10\n"
            + "3;Sveže voće i povrće;" + LEMON_NET + ";Testna;8600000003018;KOM;Test format;149,99;;13-09-2026;299,98;;;10\n"
            + "3;Sveže voće i povrće;" + AVOCADO + ";;;KOM;Test format;99,99;;13-09-2026;99,99;;;10\n"
            + "3;Sveže voće i povrće;" + LOOSE_AVOCADO + ";;;KG;Test format;749,99;;13-09-2026;749,99;;;10\n"
            + "3;Sveže voće i povrće;" + LOOSE_APPLES + ";;;KG;Test format;119,99;;13-09-2026;119,99;;;10\n";

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
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/pieces.csv", exchange -> {
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
                        VALUES ('PIECES', 'Pieces', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/pieces.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("PIECES").status())
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
                        VALUES (?, ?, 'PIECES-1', 'Pieces objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    @Test
    void countedFruitIsPiecesWeighedWhereTheShopWeighs() {
        long shop = shop();

        var list = shoppingListService.create(new CreateShoppingListRequest("Voće"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        limun 3
                        avokado 2
                        3 limuna
                        limun
                        jabuke 2
                        """)
        );

        var items = shoppingListService.findById(list.id(), callers.account(TOKEN)).items();
        assertThat(items.get(0).flexibleConstraints()).satisfies(lemons -> {
            assertThat(lemons.targetQuantity()).isEqualByComparingTo("3");
            assertThat(lemons.requiredBaseUnit()).isEqualTo("piece");
        });
        assertThat(items.get(0).quantity()).isEqualByComparingTo("1");
        // A line with no count is still a kilogram weighed out (V137).
        assertThat(items.get(3).flexibleConstraints().targetQuantity()).isNull();

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).hasSize(5);

        // Three lemons weighed out are 0,3 kg, not 3 kg (389,97) or a 500 g net.
        assertThat(offers.get(0)).satisfies(lemons -> {
            assertThat(lemons.productName()).isEqualTo(LOOSE_LEMONS);
            assertThat(lemons.purchaseQuantity().packages()).isEqualByComparingTo("0.3");
            assertThat(lemons.purchaseQuantity().baseUnit()).isEqualTo("g");
            assertThat(lemons.purchaseQuantity().targetAmount()).isEqualByComparingTo("300");
            assertThat(lemons.purchaseQuantity().suppliedAmount()).isEqualByComparingTo("300");
            assertThat(lemons.lineTotal()).isEqualByComparingTo("39.00");
        });
        // Two avocados sold by the piece are two pieces, 199,98, not 1,5 kg.
        assertThat(offers.get(1)).satisfies(avocados -> {
            assertThat(avocados.productName()).isEqualTo(AVOCADO);
            assertThat(avocados.purchaseQuantity().packages()).isEqualByComparingTo("2");
            assertThat(avocados.purchaseQuantity().baseUnit()).isEqualTo("piece");
            assertThat(avocados.lineTotal()).isEqualByComparingTo("199.98");
        });
        assertThat(offers.get(2).lineTotal()).isEqualByComparingTo("39.00");
        assertThat(offers.get(3)).satisfies(lemons -> {
            assertThat(lemons.productName()).isEqualTo(LOOSE_LEMONS);
            assertThat(lemons.lineTotal()).isEqualByComparingTo("129.99");
        });
        // Two apples, 2 × 180 g.
        assertThat(offers.get(4)).satisfies(apples -> {
            assertThat(apples.productName()).isEqualTo(LOOSE_APPLES);
            assertThat(apples.purchaseQuantity().packages()).isEqualByComparingTo("0.36");
            assertThat(apples.lineTotal()).isEqualByComparingTo("43.20");
        });
    }
}
