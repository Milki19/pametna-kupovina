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
 * The cases from the test of 14.10.2026: a word on a list means the usual kind
 * of product (ground coffee, white sugar, plain bananas), loose goods are
 * weighed out, thirteen sachets are not a 200 g pack, and laundry powder,
 * shampoo, toilet paper, Coca-Cola and T-400 flour are kinds of their own (V125).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class UsualKindOfProductTest {

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

    private static final String TOKEN = "usual-kind";
    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static final String ICE_COFFEE = "Ice Coffee Freeze Grand 16g";
    private static final String COFFEE_BASED = "PROIZVOD NA BAZI KAFE 200G";
    private static final String COFFEE_SACHET = "Kafa mlevena kesica 20g";
    private static final String GROUND_COFFEE = "Grand kafa mlevena 200g";
    private static final String POWDERED_SUGAR = "Secer u prahu 500g";
    private static final String WHITE_SUGAR = "Secer kristal 1kg";
    private static final String VANILLA_SUGAR = "Vanilin secer 10g";
    private static final String BANANAS = "BANANA";
    private static final String RED_BANANAS = "500G CRVENA BANANA";
    private static final String DETERGENT = "Ariel prasak za ves 3kg";
    private static final String BAKING_POWDER = "Prasak za pecivo 12g";
    private static final String SHAMPOO = "Head Shoulders sampon 400ml";
    private static final String TOILET_PAPER = "Toaletni papir Perfex 10/1";
    private static final String COLA = "Coca Cola 2l";
    private static final String SMALL_COLA = "Coca Cola 0,5l";
    private static final String PEPSI = "Pepsi 2l";
    private static final String FLOUR_400 = "Brasno T-400 1kg";
    private static final String FLOUR_500 = "Brasno T-500 1kg";

    private static final String CATALOGUE = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n"
            + row("", ICE_COFFEE, "KOM", "29,99")
            + row("", COFFEE_BASED, "KOM", "199,99")
            + row("", COFFEE_SACHET, "KOM", "39,99")
            + row("", GROUND_COFFEE, "KOM", "459,99")
            + row("", POWDERED_SUGAR, "KOM", "89,99")
            + row("", WHITE_SUGAR, "KOM", "119,99")
            + row("", VANILLA_SUGAR, "KOM", "9,99")
            + "3;Sveže voće i povrće;" + BANANAS + ";;;KG;Test format;149,99;;13-09-2026;149,99;;;10\n"
            + "3;Sveže voće i povrće;" + RED_BANANAS + ";;;KOM;Test format;329,99;;13-09-2026;659,98;;;10\n"
            + row("", DETERGENT, "KOM", "1299,99")
            + row("", BAKING_POWDER, "KOM", "19,99")
            + row("", SHAMPOO, "KOM", "499,99")
            + row("", TOILET_PAPER, "KOM", "349,99")
            + row("", COLA, "KOM", "189,99")
            + row("", SMALL_COLA, "KOM", "99,99")
            + row("", PEPSI, "KOM", "149,99")
            + row("", FLOUR_400, "KOM", "79,99")
            + row("", FLOUR_500, "KOM", "69,99");

    private static int barcode = 1000;

    private static String row(String category, String name, String unit, String price) {
        barcode++;
        return category + ";;" + name + ";Testna;86000000" + barcode + ";" + unit
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
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/usual.csv", exchange -> {
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
                        VALUES ('USUAL', 'Usual', ?)
                        RETURNING id
                        """)
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/usual.csv")
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices("USUAL").status())
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
                        VALUES (?, ?, 'USUAL-1', 'Usual objekat', 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .query(Long.class)
                .single();
    }

    private String typeOf(String productName) {
        return jdbcClient.sql("""
                        SELECT type.code
                        FROM app.retailer_product AS product
                        LEFT JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        LEFT JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        WHERE product.name = ?
                        """)
                .param(productName)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    @Test
    void aWordOnTheListMeansTheUsualKind() {
        long shop = shop();

        assertThat(typeOf(ICE_COFFEE)).isEqualTo("INSTANT_COFFEE");
        assertThat(typeOf(COFFEE_BASED)).isNull();
        assertThat(typeOf(GROUND_COFFEE)).isEqualTo("COFFEE");
        assertThat(typeOf(VANILLA_SUGAR)).isNull();
        assertThat(typeOf(DETERGENT)).isEqualTo("LAUNDRY_DETERGENT");
        assertThat(typeOf(SHAMPOO)).isEqualTo("SHAMPOO");
        assertThat(typeOf(TOILET_PAPER)).isEqualTo("TOILET_PAPER");
        assertThat(typeOf(COLA)).isEqualTo("SOFT_DRINK");

        var list = shoppingListService.create(new CreateShoppingListRequest("Test 14.10."), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        kafa 200 g
                        šećer 500 g
                        banane 1,5 kg
                        prasak za ves
                        sampon za kosu
                        toalet papir
                        coca cola 2l
                        BRAŠNO T-400 1 kg
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).hasSize(8);

        // Ten 20 g sachets cost 399,90 on the shelf, but every pack beyond
        // three weighs 5% more: the 200 g pack wins.
        assertThat(offers.get(0).productName()).isEqualTo(GROUND_COFFEE);
        // Only a kilogram of white sugar beats 500 g of powdered sugar.
        assertThat(offers.get(1)).satisfies(sugar -> {
            assertThat(sugar.productName()).isEqualTo(WHITE_SUGAR);
            assertThat(sugar.purchaseQuantity().packages()).isEqualByComparingTo("1");
        });
        // Plain bananas, weighed out: 1,5 × 149,99.
        assertThat(offers.get(2)).satisfies(bananas -> {
            assertThat(bananas.productName()).isEqualTo(BANANAS);
            assertThat(bananas.purchaseQuantity().packages()).isEqualByComparingTo("1.5");
            assertThat(bananas.lineTotal()).isEqualByComparingTo("224.99");
        });
        assertThat(offers.get(3).productName()).isEqualTo(DETERGENT);
        assertThat(offers.get(4).productName()).isEqualTo(SHAMPOO);
        assertThat(offers.get(5).productName()).isEqualTo(TOILET_PAPER);
        // Two litres of Coca-Cola, not the cheaper Pepsi.
        assertThat(offers.get(6)).satisfies(cola -> {
            assertThat(cola.productName()).isEqualTo(COLA);
            assertThat(cola.purchaseQuantity().packages()).isEqualByComparingTo("1");
        });
        // T-400, not the cheaper T-500.
        assertThat(offers.get(7).productName()).isEqualTo(FLOUR_400);
    }

    @Test
    void anUnusualKindStillAnswersWhereItIsAllThereIs() {
        long shop = jdbcClient.sql("SELECT id FROM app.store WHERE external_code = 'USUAL-1'")
                .query(Long.class)
                .optional()
                .orElseGet(this::shop);

        var list = shoppingListService.create(new CreateShoppingListRequest("Šećer u prahu"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        šećer u prahu
                        """)
        );

        List<StoreItemOffer> offers = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shop),
                PRICE_DATE
        );
        assertThat(offers).singleElement()
                .satisfies(sugar -> assertThat(sugar.productName()).isEqualTo(POWDERED_SUGAR));
    }
}
