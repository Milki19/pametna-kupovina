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
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "riba 4" in "Jedna stanica" was four sea bass for 12.520 RSD (provera
 * uživo S18, 10.11.2026). Few fish in a shop are named "riba", so the word
 * was kept as a narrower one, like "kajmak" among the creams, and the line
 * lost the limit of a plain line (S6). "riba" names the whole kind (V165).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class PlainFishLineTest {

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

    private static final String TOKEN = "plain-fish-line";

    private static final String HEADER = "KATEGORIJA;NAZIV KATEGORIJE;"
            + "Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;"
            + "Naziv trgovca - formata*;Redovna cena;Snižena cena;"
            + "Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;"
            + "Datum kraja sniženja;Stopa PDV\n";

    private static int barcode = 7000;

    /** As a shop names its fish: one in five says "riba". */
    private static final String CATALOGUE = HEADER
            + row("BRANCIN SVEZ NA LEDU 1KG", "3129,99")
            + row("PASTRMKA CELA NA LEDU 1KG", "799,99")
            + row("SARAN OCISCEN NA LEDU 1KG", "749,99")
            + row("ORADA SVEZA 1KG", "1899,99")
            + row("SVEZA RIBA MIX 1KG", "899,99");

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
    private BaseKindPrices baseKindPrices;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/fish.csv", exchange -> {
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
    void plainFishIsJudgedByWhatFishUsuallyCosts() {
        jdbcClient.sql("INSERT INTO app.retailer (code, name, dataset_url) VALUES ('FISHSHOP', 'Riblja', ?)")
                .param("http://127.0.0.1:" + csvServer.getAddress().getPort() + "/fish.csv")
                .update();
        assertThat(priceImportService.importPrices("FISHSHOP").status()).isEqualTo("SUCCEEDED");
        // The import leaves typing to the catalog upkeep; type the fish as it does.
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
                         AND chain.code = 'FISHSHOP'
                        ON CONFLICT (retailer_product_id) DO NOTHING
                        """)
                .update();

        // The case that misled the list: fish are typed, few are named "riba".
        BigDecimal share = jdbcClient.sql("""
                        SELECT COALESCE(AVG(CASE WHEN product.normalized_name ~ '\\mriba\\M' THEN 1.0 ELSE 0.0 END), -1)
                        FROM app.shopping_intent AS intent
                        JOIN app.shopping_intent_product_type AS allowed
                          ON allowed.shopping_intent_id = intent.id
                         AND allowed.enabled_by_default
                        JOIN app.retailer_product_type AS assignment
                          ON assignment.product_type_id = allowed.product_type_id
                        JOIN app.retailer_product AS product
                          ON product.id = assignment.retailer_product_id
                        WHERE intent.code = 'FISH'
                        """)
                .query(BigDecimal.class)
                .single();
        String typed = jdbcClient.sql("""
                        SELECT COALESCE(STRING_AGG(product.name || ' = ' || COALESCE(kind.code, '-'), ', '), 'nema proizvoda')
                        FROM app.retailer_product AS product
                        JOIN app.retailer AS chain ON chain.id = product.retailer_id AND chain.code = 'FISHSHOP'
                        LEFT JOIN app.retailer_product_type AS assignment ON assignment.retailer_product_id = product.id
                        LEFT JOIN app.product_type AS kind ON kind.id = assignment.product_type_id
                        """)
                .query(String.class)
                .single();
        assertThat(share).as(typed).isPositive().isLessThan(new BigDecimal("0.5"));

        var list = shoppingListService.create(new CreateShoppingListRequest("Riba"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("riba 4\nriba brancin\n")
        );
        var items = shoppingListService.findById(list.id(), callers.account(TOKEN)).items();

        Map<String, Object> fish = jdbcClient.sql("""
                        SELECT wanted_name_pattern, flexible_category_normalized, shopping_intent_id
                        FROM app.shopping_list_item WHERE id = ?
                        """)
                .param(items.get(0).id())
                .query().singleRow();
        assertThat(fish.get("wanted_name_pattern")).as("%s", fish).isNull();

        Map<Long, BaseKindPrices.PlainKind> plain = baseKindPrices.plainLines(list.id());
        assertThat(plain).as("%s", fish).containsKey(items.get(0).id());
        // "riba brancin" asks for more than the kind and keeps its own offers.
        assertThat(plain).doesNotContainKey(items.get(1).id());
    }
}
