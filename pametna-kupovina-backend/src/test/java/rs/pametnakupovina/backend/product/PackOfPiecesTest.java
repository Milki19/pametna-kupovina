package rs.pametnakupovina.backend.product;

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
import rs.pametnakupovina.backend.shoppinglist.AddShoppingListItemRequest;
import rs.pametnakupovina.backend.shoppinglist.CreateShoppingListRequest;
import rs.pametnakupovina.backend.shoppinglist.FlexibleItemConstraints;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemRule;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.shoppinglist.StoreItemOffer;
import rs.pametnakupovina.backend.shoppinglist.StoreShoppingOfferRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One chain sells Corona by the bottle, the other only as "0.33L CORONA NB
 * 6/1", a name that says neither "pivo" nor whether 0,33 l is one bottle or
 * all six. The bottle next door answers both: the case holds six bottles, and
 * it is beer. Someone who asks for six Corona then gets six bottles in one
 * shop and the case in the other, and the prices decide.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PackOfPiecesTest {

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

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static final String BY_THE_BOTTLE = HEADER + """
            PIVO;Pivo;PIVO CORONA 0.33L NB;Corona;8601234000082;KOM;Test format;239,99;;13-09-2026;727,24;;;20
            PIVO;Pivo;PIVO LAV PREMIUM 0.5L PB;Lav;8601234000099;KOM;Test format;99,99;;13-09-2026;199,98;;;20
            NUDLE;Nudle;NUDLE UKUS PILETINE INDOMIE 70G;Indomie;;KOM;Test format;59,99;;13-09-2026;857,00;;;20
            NUDLE;Nudle;NUDLE UKUS PILETINE INDOMIE 700G 10/1;Indomie;;KOM;Test format;520,00;;13-09-2026;742,86;;;20
            CAJ;Caj;CAJ AHMAD LEMON 20/1 40G;Ahmad;;KOM;Test format;459,00;;13-09-2026;11475,00;;;20
            JAJA;Jaja;JAJA KONZUMNA M 10 KOM;Farma;;KOM;Test format;249,99;;13-09-2026;25,00;;;10
            """;

    private static final String BY_THE_CASE = HEADER + """
            ;;0.33L CORONA NB 6/1-STAND.-VAR.;CORONA;8601234000105;L;Test format;1200,00;;13-09-2026;606,06;;;20
            JAJA;Jaja;TECNO CELO JAJE 1L;Farma;;L;Test format;199,99;;13-09-2026;199,99;;;10
            """;

    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private ProductCatalogMaintenanceService catalogMaintenanceService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/bottles.csv", BY_THE_BOTTLE);
        serve("/cases.csv", BY_THE_CASE);
        csvServer.setExecutor(Executors.newSingleThreadExecutor());
        csvServer.start();
    }

    private static void serve(String path, String csv) {
        csvServer.createContext(path, exchange -> {
            byte[] body = csv.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @AfterAll
    static void stopCsvServer() {
        if (csvServer != null) {
            csvServer.stop(0);
        }
    }

    private long importChain(String code, String path) {
        long retailerId = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES (?, ?, ?)
                        RETURNING id
                        """)
                .param(code)
                .param(code)
                .param("http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + path)
                .query(Long.class)
                .single();

        assertThat(priceImportService.importPrices(code).status())
                .isEqualTo("SUCCEEDED");

        return retailerId;
    }

    /** A shop without a known address: a plan still prices its basket. */
    private long shopOf(long retailerId, String code) {
        long formatId = jdbcClient.sql("""
                        INSERT INTO app.store_format (retailer_id, code, name)
                        VALUES (?, 'TEST', 'Test format')
                        RETURNING id
                        """)
                .param(retailerId)
                .query(Long.class)
                .single();

        return jdbcClient.sql("""
                        INSERT INTO app.store (
                            retailer_id,
                            store_format_id,
                            external_code,
                            name,
                            address,
                            city,
                            active
                        )
                        VALUES (?, ?, ?, ?, 'Test adresa', 'Beograd', TRUE)
                        RETURNING id
                        """)
                .param(retailerId)
                .param(formatId)
                .param(code + "-1")
                .param(code + " objekat")
                .query(Long.class)
                .single();
    }

    private Map<String, Object> product(String name) {
        return jdbcClient.sql("""
                        SELECT product.id,
                               product.quantity_value,
                               product.package_count,
                               product.package_unit_product_id,
                               type.code AS type_code,
                               assignment.assignment_source
                        FROM app.retailer_product AS product
                        LEFT JOIN app.retailer_product_type AS assignment
                          ON assignment.retailer_product_id = product.id
                        LEFT JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        WHERE product.name = ?
                        """)
                .param(name)
                .query()
                .singleRow();
    }

    private static void assertSize(
            Map<String, Object> row,
            String quantity,
            int packageCount
    ) {
        assertThat((BigDecimal) row.get("quantity_value"))
                .isEqualByComparingTo(quantity);
        assertThat(row.get("package_count")).isEqualTo(packageCount);
    }

    private List<StoreItemOffer> planFor(
            String name,
            String brand,
            String pieces,
            List<Long> shops
    ) {
        var list = shoppingListService.create(
                new CreateShoppingListRequest(name),
                "pack-of-pieces"
        );
        shoppingListService.addItem(
                list.id(),
                "pack-of-pieces",
                new AddShoppingListItemRequest(
                        name,
                        name,
                        null,
                        null,
                        BigDecimal.ONE,
                        ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints(
                                name,
                                brand,
                                null,
                                null,
                                "piece",
                                new BigDecimal(pieces)
                        )
                )
        );
        return offerRepository.findPriceListOffers(list.id(), shops, PRICE_DATE);
    }

    @Test
    void aCaseIsReadAgainstItsBottleAndComparedWithSixBottles() {
        long bottles = importChain("BOTTLES", "/bottles.csv");
        long cases = importChain("CASES", "/cases.csv");

        Map<String, Object> bottle = product("PIVO CORONA 0.33L NB");
        Map<String, Object> corona = product("0.33L CORONA NB 6/1-STAND.-VAR.");
        // Six bottles of 330 ml, and beer like the bottle it was read against.
        assertSize(corona, "1980", 6);
        assertThat(corona.get("package_unit_product_id")).isEqualTo(bottle.get("id"));
        assertThat(corona.get("type_code")).isEqualTo("BEER");
        assertThat(corona.get("assignment_source")).isEqualTo("PACKAGE_UNIT");
        // 700 g in ten bags of 70 g: the size was already the whole pack.
        assertSize(product("NUDLE UKUS PILETINE INDOMIE 700G 10/1"), "700", 10);
        // No single tea bag to tell: the name is left alone.
        assertSize(product("CAJ AHMAD LEMON 20/1 40G"), "40", 1);

        // Refreshing again without a new price list keeps every reading.
        catalogMaintenanceService.refreshRetailer(cases);
        catalogMaintenanceService.refreshRetailer(bottles);
        assertSize(product("0.33L CORONA NB 6/1-STAND.-VAR."), "1980", 6);
        assertSize(product("NUDLE UKUS PILETINE INDOMIE 700G 10/1"), "700", 10);

        List<Long> shops = List.of(
                shopOf(bottles, "BOTTLES"),
                shopOf(cases, "CASES")
        );

        List<StoreItemOffer> beer = planFor("pivo", "Corona", "6", shops);
        assertThat(beer)
                .filteredOn(offer -> offer.retailerCode().equals("BOTTLES"))
                .singleElement()
                .satisfies(offer -> {
                    assertThat(offer.productName()).isEqualTo("PIVO CORONA 0.33L NB");
                    assertThat(offer.purchaseQuantity().packages()).isEqualByComparingTo("6");
                    assertThat(offer.lineTotal()).isEqualByComparingTo("1439.94");
                });
        assertThat(beer)
                .filteredOn(offer -> offer.retailerCode().equals("CASES"))
                .singleElement()
                .satisfies(offer -> {
                    assertThat(offer.purchaseQuantity().packages()).isEqualByComparingTo("1");
                    assertThat(offer.purchaseQuantity().packageSize()).isEqualByComparingTo("6");
                    assertThat(offer.purchaseQuantity().baseUnit()).isEqualTo("piece");
                    assertThat(offer.purchaseQuantity().unitPrice()).isEqualByComparingTo("200.00");
                    assertThat(offer.lineTotal()).isEqualByComparingTo("1200.00");
                });

        // Eggs are counted as they are sold. A litre of liquid egg is eggs by
        // type but not ten eggs, so the shop that only has that has no eggs.
        assertThat(product("TECNO CELO JAJE 1L").get("type_code")).isEqualTo("EGGS");
        List<StoreItemOffer> eggs = planFor("jaja", null, "10", shops);
        assertThat(eggs)
                .filteredOn(offer -> offer.retailerCode().equals("BOTTLES"))
                .singleElement()
                .satisfies(offer -> assertThat(offer.productName())
                        .isEqualTo("JAJA KONZUMNA M 10 KOM"));
        assertThat(eggs)
                .filteredOn(offer -> offer.retailerCode().equals("CASES"))
                .singleElement()
                .satisfies(offer -> assertThat(offer.available()).isFalse());
    }
}
