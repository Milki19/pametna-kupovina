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
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Four chains price one 25 kg bag of sugar, and one of them publishes the
 * price of a kilogram: 89,90 against about 2.400. That price must not be the
 * cheapest anywhere. The product screen lists it last and marked, the search
 * does not offer it as the chain's price, and a shopping plan does not buy the
 * bag there.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PriceCheckTest {

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

    private static final String BARCODE = "8601234000075";

    private static final LocalDate PRICE_DATE = LocalDate.of(2026, 9, 13);

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private CanonicalProductDetailsService detailsService;

    @Autowired
    private CanonicalProductSearchService searchService;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private StoreShoppingOfferRepository offerRepository;

    private static String priceList(String price) {
        return "KATEGORIJA;NAZIV KATEGORIJE;"
                + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
                + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
                + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
                + "Datum kraja sniženja;Stopa PDV\n"
                + "1;Secer;SECER KRISTAL 25KG SUNOKO;Sunoko;" + BARCODE
                + ";KOM;Test format;" + price + ";;13-09-2026;" + price
                + ";;;10\n";
    }

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        serve("/a.csv", priceList("2449,99"));
        serve("/b.csv", priceList("2379,00"));
        serve("/c.csv", priceList("2499,99"));
        serve("/d.csv", priceList("89,90"));
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

    @Test
    void aPriceStatedOnAnotherBasisIsNeverTheCheapest() {
        importChain("CHECK_A", "/a.csv");
        long chainB = importChain("CHECK_B", "/b.csv");
        importChain("CHECK_C", "/c.csv");
        long chainD = importChain("CHECK_D", "/d.csv");

        long productId = jdbcClient.sql("""
                        SELECT id
                        FROM app.canonical_product
                        WHERE barcode = ?
                        """)
                .param(BARCODE)
                .query(Long.class)
                .single();

        // The median of the four chains' prices.
        assertThat(jdbcClient.sql("""
                        SELECT typical.typical_price
                        FROM app.product_family_typical_price AS typical
                        JOIN app.product_family_member AS member
                          ON member.family_id = typical.product_family_id
                        WHERE member.canonical_product_id = ?
                        """)
                .param(productId)
                .query(BigDecimal.class)
                .single())
                .isEqualByComparingTo("2414.50");

        List<CanonicalProductOffer> offers = detailsService
                .find(productId, PRICE_DATE, 0)
                .orElseThrow()
                .offers();
        assertThat(offers)
                .extracting(CanonicalProductOffer::retailerCode)
                .containsExactly("CHECK_B", "CHECK_A", "CHECK_C", "CHECK_D");
        assertThat(offers)
                .extracting(CanonicalProductOffer::priceNeedsCheck)
                .containsExactly(false, false, false, true);

        assertThat(searchService.search("secer kristal", 0, 10)
                .items()
                .getFirst()
                .availability())
                .filteredOn(ProductRetailerAvailability::priceNeedsCheck)
                .extracting(ProductRetailerAvailability::retailerCode)
                .containsExactly("CHECK_D");

        var list = shoppingListService.create(
                new CreateShoppingListRequest("Šećer"),
                "price-check"
        );
        shoppingListService.addItem(
                list.id(),
                "price-check",
                new AddShoppingListItemRequest(
                        "Šećer 25 kg",
                        "Šećer 25 kg",
                        null,
                        productId,
                        BigDecimal.ONE,
                        ShoppingItemRule.EXACT_PRODUCT,
                        null
                )
        );

        List<StoreItemOffer> planned = offerRepository.findPriceListOffers(
                list.id(),
                List.of(shopOf(chainB, "CHECK_B"), shopOf(chainD, "CHECK_D")),
                PRICE_DATE
        );
        assertThat(planned)
                .filteredOn(offer -> offer.retailerCode().equals("CHECK_B"))
                .singleElement()
                .satisfies(offer -> assertThat(offer.lineTotal())
                        .isEqualByComparingTo("2379.00"));
        assertThat(planned)
                .filteredOn(offer -> offer.retailerCode().equals("CHECK_D"))
                .singleElement()
                .satisfies(offer -> assertThat(offer.available()).isFalse());
    }
}
