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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "puter" found only the butter named "puter" (sitnica F3 posle šestog
 * testa, 11.11.2026): fewer than half of the butter says the word, so it was
 * kept as a narrower one, like "kajmak" among the creams. Shops write
 * "maslac" for the same thing (app.same_word, V168).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PuterFindsMaslacTest {

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

    private static final String TOKEN = "puter-finds-maslac";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7100;

    /** As shops name their butter: one in four says "puter". */
    private static final String CATALOGUE = HEADER
            + row("MASLAC MOJA KRAVICA 250G", "389,99")
            + row("MASLAC DUKAT 200G", "329,99")
            + row("MASLAC IMLEK 250G", "359,99")
            + row("PUTER BISER 250G", "449,99");

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
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/butter.csv", exchange -> {
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
    void puterIsTheWholeKindBecauseShopsSayMaslacToo() {
        jdbcClient.sql("INSERT INTO app.retailer (code, name, dataset_url) VALUES ('BUTTERSHOP', 'Mlekara', ?)")
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/butter.csv")
                .update();
        assertThat(priceImportService.importPrices("BUTTERSHOP").status()).isEqualTo("SUCCEEDED");
        // The import leaves typing to the catalog upkeep; type the butter as it does.
        jdbcClient.sql("""
                        INSERT INTO app.retailer_product_type (
                            retailer_product_id, product_type_id, confidence,
                            assignment_source, evidence, algorithm_version, retailer_id
                        )
                        SELECT prediction.retailer_product_id, prediction.product_type_id,
                               prediction.confidence, prediction.prediction_source,
                               prediction.evidence, prediction.algorithm_version, product.retailer_id
                        FROM app.predict_product_types(NULL) AS prediction
                        JOIN app.retailer_product AS product
                          ON product.id = prediction.retailer_product_id
                        JOIN app.retailer AS chain
                          ON chain.id = product.retailer_id
                         AND chain.code = 'BUTTERSHOP'
                        ON CONFLICT (retailer_product_id) DO NOTHING
                        """)
                .update();

        // The case that misled the list: the butter is typed, few say "puter".
        BigDecimal share = jdbcClient.sql("""
                        SELECT COALESCE(AVG(CASE WHEN product.normalized_name ~ '\\mputer\\M' THEN 1.0 ELSE 0.0 END), -1)
                        FROM app.shopping_intent AS intent
                        JOIN app.shopping_intent_product_type AS allowed
                          ON allowed.shopping_intent_id = intent.id
                         AND allowed.enabled_by_default
                        JOIN app.retailer_product_type AS assignment
                          ON assignment.product_type_id = allowed.product_type_id
                        JOIN app.retailer_product AS product
                          ON product.id = assignment.retailer_product_id
                        WHERE intent.code = 'BUTTER'
                        """)
                .query(BigDecimal.class)
                .single();
        assertThat(share).isPositive().isLessThan(new BigDecimal("0.5"));

        var list = shoppingListService.create(new CreateShoppingListRequest("Puter"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("puter\nmaslac\n")
        );
        var items = shoppingListService.findById(list.id(), callers.account(TOKEN)).items();

        assertThat(items).hasSize(2);
        for (var item : items) {
            Map<String, Object> butter = jdbcClient.sql("""
                            SELECT wanted_name_pattern, flexible_category_normalized, shopping_intent_id
                            FROM app.shopping_list_item WHERE id = ?
                            """)
                    .param(item.id())
                    .query().singleRow();
            assertThat(butter.get("shopping_intent_id")).as("%s", butter).isNotNull();
            assertThat(butter.get("wanted_name_pattern")).as("%s", butter).isNull();
        }

        List<String> said = jdbcClient.sql("""
                        SELECT name_word FROM app.same_word
                        WHERE language = 'sr-Latn' AND market_id IS NULL AND normalized_word = 'puter'
                        ORDER BY name_word
                        """)
                .query(String.class)
                .list();
        assertThat(said).containsExactly("maslac", "puter");
    }
}
