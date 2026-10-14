package rs.pametnakupovina.backend.priceimport;

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

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goods of variable weight are quoted per kilogram with both price columns
 * repeating that figure, so reading them as a package price is badly wrong:
 * METRO's 15kg frozen fillet at 571,99 would come out at 38 dinars a kilo.
 * The same repetition WITHOUT "cca" means something else entirely — a 200g box
 * of pralines really does cost what the row says — and that case is far more
 * common, so the correction must not touch it.
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class VariableWeightPricingTest {

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

    private static final String CSV = """
            KATEGORIJA;NAZIV KATEGORIJE;Naziv proizvoda;Robna marka;Barkod proizvoda;Jedinica mere;Naziv trgovca - formata*;Redovna cena;Snižena cena;Datum cenovnika;Cena po jedinici mere;Datum početka sniženja;Datum kraja sniženja;Stopa PDV
            8;Meso;CCA 15KG ZAMRZNUTI PILECI FILE;NO BRAND;2604065701011;KG;Test format;571,99;;13-09-2026;571,99;;;10
            8;Meso;PILECI FILE CCA 3KG VAKUUM;NO BRAND;2604065701012;KG;Test format;698,49;;13-09-2026;698,49;;;10
            9;Slatkisi;Bombonjera Ferrero Rocher T16 200g;Ferrero;2604065701013;kg;Test format;861,99;;13-09-2026;861,99;;;20
            1;Mleko;Mleko 1l;Test;2604065701014;L;Test format;99,99;;13-09-2026;99,99;;;10
            """;

    private static HttpServer csvServer;

    @Autowired
    private PriceImportService priceImportService;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeAll
    static void startCsvServer() throws IOException {
        csvServer = HttpServer.create(new InetSocketAddress(0), 0);
        csvServer.createContext("/prices.csv", exchange -> {
            byte[] body = CSV.getBytes(StandardCharsets.UTF_8);
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

    private BigDecimal priceOf(String barcode) {
        return jdbcClient.sql("""
                        SELECT offer.regular_price
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        WHERE product.barcode = ?
                        """)
                .param(barcode)
                .query(BigDecimal.class)
                .single();
    }

    @Test
    void perKilogramPricesBecomePackagePricesOnlyForVariableWeightGoods() {
        jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name, dataset_url)
                        VALUES ('VARWEIGHT', 'Variable weight test', ?)
                        """)
                .param("http://127.0.0.1:"
                        + csvServer.getAddress().getPort()
                        + "/prices.csv")
                .update();

        ImportResult result = priceImportService.importPrices("VARWEIGHT");
        assertThat(result.status()).isEqualTo("SUCCEEDED");

        // 571,99 a kilo over roughly 15kg, not 571,99 for the whole box.
        assertThat(priceOf("2604065701011"))
                .isEqualByComparingTo("8579.85");
        assertThat(priceOf("2604065701012"))
                .isEqualByComparingTo("2095.47");

        // No "cca": the row already states what the package costs.
        assertThat(priceOf("2604065701013"))
                .isEqualByComparingTo("861.99");

        // Exactly one unit of measure: nothing to scale either way.
        assertThat(priceOf("2604065701014"))
                .isEqualByComparingTo("99.99");
    }

    private Boolean needsCheck(String regular, String discounted, String typical) {
        return jdbcClient.sql("SELECT app.price_needs_check(?::NUMERIC, ?::NUMERIC, ?::NUMERIC)")
                .param(1, regular, Types.VARCHAR)
                .param(2, discounted, Types.VARCHAR)
                .param(3, typical, Types.VARCHAR)
                .query(Boolean.class)
                .single();
    }

    private BigDecimal factor(String name, String price, String grams, String typical) {
        return jdbcClient.sql("""
                        SELECT app.price_per_unit_factor(
                            ?, ?::NUMERIC, ?::NUMERIC, 'g', ?::NUMERIC
                        )
                        """)
                .param(1, name, Types.VARCHAR)
                .param(2, price, Types.VARCHAR)
                .param(3, grams, Types.VARCHAR)
                .param(4, typical, Types.VARCHAR)
                .query(BigDecimal.class)
                .list()
                .getFirst();
    }

    @Test
    void aSaleTooDeepToBelieveIsCheckedFirst() {
        // Soap 96 -> 10: over 80% off.
        assertThat(needsCheck("96", "10", null)).isTrue();
        // Under half of what it typically costs.
        assertThat(needsCheck("100", "40", "100")).isTrue();
        // A real sale.
        assertThat(needsCheck("100", "55", "100")).isFalse();
        assertThat(needsCheck("100", "25", null)).isFalse();
        // Under a tenth the sale is a typo and the regular price stands (V115).
        assertThat(needsCheck("96", "9", null)).isFalse();
    }

    @Test
    void aCcaPieceQuotedPerKilogramIsReadAgainstTheMarket() {
        // 899,99 a kilogram of a 3,3 kg cheese whose piece typically costs 4.500.
        assertThat(factor("paladin edamer cca 3 3kg", "899.99", "3300", "4500"))
                .isEqualByComparingTo("3.3");
        // Already the piece's price.
        assertThat(factor("paladin edamer cca 3 3kg", "2969.97", "3300", "4500"))
                .isNull();
        // A bag of fixed weight is not guessed at; it stays doubted (V72).
        assertThat(factor("secer kristal 25kg", "89.90", "25000", "2414.50"))
                .isNull();
        // A price per tablet or bottle is far off either way.
        assertThat(factor("sir cca 3kg", "24.90", "3000", "2400"))
                .isNull();
    }

    private long chainWithProduct(String code, long family, String name, int grams, String price) {
        long chain = jdbcClient.sql("INSERT INTO app.retailer (code, name) VALUES (?, ?) RETURNING id")
                .params(code, code)
                .query(Long.class).single();
        long run = jdbcClient.sql("""
                        INSERT INTO app.import_run (retailer_id, source_url, status)
                        VALUES (?, 'https://example.test/family', 'SUCCEEDED') RETURNING id
                        """)
                .param(chain)
                .query(Long.class).single();
        long product = jdbcClient.sql("""
                        INSERT INTO app.retailer_product (
                            retailer_id, source_product_key, name, normalized_name,
                            quantity_value, base_unit, product_family_id
                        )
                        VALUES (?, ?, ?, ?, ?, 'g', ?) RETURNING id
                        """)
                .params(chain, name, name, name, grams, family)
                .query(Long.class).single();
        jdbcClient.sql("""
                        INSERT INTO app.current_price_offer (
                            retailer_product_id, import_run_id, scope_type,
                            retailer_format_name, price_date, first_seen_date,
                            last_seen_date, regular_price
                        )
                        VALUES (?, ?, 'STORE_FORMAT', 'Test', CURRENT_DATE, CURRENT_DATE, CURRENT_DATE, ?::NUMERIC)
                        """)
                .params(product, run, price)
                .update();
        return product;
    }

    private long family(String key) {
        return jdbcClient.sql("""
                        INSERT INTO app.product_family (family_key, display_name, normalized_name)
                        VALUES (?, ?, ?) RETURNING id
                        """)
                .params(key, key, key)
                .query(Long.class).single();
    }

    private BigDecimal factorOf(long retailerProductId, String price) {
        return jdbcClient.sql("SELECT app.price_per_unit_factor(?, ?::NUMERIC)")
                .params(retailerProductId, price)
                .query(BigDecimal.class)
                .list()
                .getFirst();
    }

    /**
     * RIČ's "PALADIN EDAMER 3,3KG" at 899,99 is the price of a kilogram:
     * Maxi sells the same cheese as "cca 3,3kg" for 4.616,70, and no third
     * chain is needed to see it (V127).
     */
    @Test
    void aPieceIsKnownToBeSoldByWeightFromItsFamily() {
        long edamer = family("TEST:paladin edamer 3300g");
        chainWithProduct("FAM-MAXI", edamer, "paladin edamer cca 3 3kg", 3300, "4616.70");
        long ric = chainWithProduct("FAM-RIC", edamer, "paladin edamer 3 3kg", 3300, "899.99");

        assertThat(factorOf(ric, "899.99")).isEqualByComparingTo("3.3");
        // Its own price, once scaled, is not read again.
        assertThat(factorOf(ric, "2969.97")).isNull();

        // A bag of fixed weight in two chains is not guessed at.
        long sugar = family("TEST:secer 25000g");
        chainWithProduct("FAM-SUGAR-A", sugar, "secer kristal 25kg", 25000, "2414.50");
        long perKilogram = chainWithProduct("FAM-SUGAR-B", sugar, "secer kristal 25kg", 25000, "89.90");
        assertThat(factorOf(perKilogram, "89.90")).isNull();

        // With no other chain there is nothing to read it against.
        long alone = family("TEST:gauda 3000g");
        long only = chainWithProduct("FAM-ALONE", alone, "gauda cca 3kg", 3000, "899.99");
        assertThat(factorOf(only, "899.99")).isNull();
    }
}
