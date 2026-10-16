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

        // A heavy piece of meat is listed as one kilogram at 571,99, not as
        // the 15 kg box at 571,99 (V127).
        assertThat(priceOf("2604065701011"))
                .isEqualByComparingTo("571.99");
        assertThat(quantityOf("2604065701011"))
                .isEqualByComparingTo("1000");
        assertThat(priceOf("2604065701012"))
                .isEqualByComparingTo("698.49");
        assertThat(quantityOf("2604065701012"))
                .isEqualByComparingTo("1000");

        // No "cca": the row already states what the package costs.
        assertThat(priceOf("2604065701013"))
                .isEqualByComparingTo("861.99");

        // Exactly one unit of measure: nothing to scale either way.
        assertThat(priceOf("2604065701014"))
                .isEqualByComparingTo("99.99");
    }

    private BigDecimal quantityOf(String barcode) {
        return jdbcClient.sql("""
                        SELECT quantity_value
                        FROM app.retailer_product
                        WHERE barcode = ?
                        """)
                .param(barcode)
                .query(BigDecimal.class)
                .single();
    }

    private BigDecimal perKilogram(String name, String grams, String price, String unitPrice) {
        return jdbcClient.sql("""
                        SELECT app.per_kilogram_factor(
                            ?, 'KG', ?::NUMERIC, 'g', ?::NUMERIC, ?::NUMERIC
                        )
                        """)
                .param(1, name, Types.VARCHAR)
                .param(2, grams, Types.VARCHAR)
                .param(3, price, Types.VARCHAR)
                .param(4, unitPrice, Types.VARCHAR)
                .query(BigDecimal.class)
                .list()
                .getFirst();
    }

    /**
     * Twenty chains quote a 3,3 kg block of gauda per kilogram, a few the
     * piece; both become one kilogram so they compare (V127).
     */
    @Test
    void aHeavyPieceOfCheeseIsListedPerKilogram() {
        // RIČ: the unit price repeats the price, so it is per kilogram.
        assertThat(perKilogram("paladin gauda blok 48 3 3 kg silbo", "3300", "1569.99", "1569.99"))
                .isEqualByComparingTo("1");
        // The piece at 3,3 times the unit price: divided by the weight.
        assertThat(perKilogram("sir tilsiter paladin cca 3 3 kg", "3300", "6596.70", "1999.00"))
                .isEqualByComparingTo("0.30303");
        // "cca" and no unit price: per kilogram.
        assertThat(perKilogram("sir gauda cca 3 kg", "3000", "1299.00", null))
                .isEqualByComparingTo("1");
        // Columns that say neither are left as written.
        assertThat(perKilogram("sir gauda blok 3 3 kg", "3300", "4500.00", "900.00"))
                .isNull();
        // Without "cca" a unit price of the price over the weight proves
        // nothing: most chains fill the column that way.
        assertThat(perKilogram("kackavalj paladin tilsiter 3 3 kg", "3300", "1599.99", "484.84"))
                .isNull();
        // Pet food is not cut by weight.
        assertThat(perKilogram("aro hrana pas sunka cca 10 kg", "10000", "120.00", "120.00"))
                .isNull();
        // Not cheese or meat, or too light to be a piece.
        assertThat(perKilogram("secer kristal 25 kg", "25000", "89.90", "89.90"))
                .isNull();
        assertThat(perKilogram("sir gauda 1 kg", "1000", "999.00", "999.00"))
                .isNull();
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
}
