package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shops write the number of bags in many ways: "35L/15", "60L18K", "20+4"
 * (free bags are counted), "151" for "15/1", "1/50", "#50". V166 read only
 * "20/1" and "10 kom", so 277 bags had no size; "15+3 KOM" was 3. A spice in
 * a zip bag is not a bag (V171).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class BagCountFormatsTest {

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

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ProductNameNormalizer normalizer;

    @Autowired
    private ProductQuantityParser parser;

    @Test
    void everyWayAShopWritesTheCountIsRead() {
        Map<String, String> expected = new LinkedHashMap<>();
        // V166 formats stay as they were.
        expected.put("KESE ZA ZAMRZIVAČ ZIPER FINO 1L 20/1", "20 piece x1");
        expected.put("KESE ZA SMEĆE TYTAN FLEX 60L 10KOM FINO", "10 piece x1");
        expected.put("FINO KESE ZAMRZIVAC 1kg", "-");
        expected.put("KESE ZA SMECE FINO 60L", "-");
        // New formats from the catalogue (proba V166 na kopiji, 11.11.2026).
        expected.put("KESA ZA SMECE U ROLNI SA MIRISOM LIMUNA 35L/15", "15 piece x1");
        expected.put("KESA ZA SMECE SA MIRISOM LAVANDE 35 L/15", "15 piece x1");
        expected.put("FINO KESE ZA SMECE GREEN LIFE EP 60L18K", "18 piece x1");
        expected.put("Fino kese za smece easy pack 60l 20+4", "24 piece x1");
        expected.put("FINO KESE ZA SMECE HD 70L 15+3 KOM U BOJI", "18 piece x1");
        expected.put("KESE ZA SMEĆE FINO 40L 151 U BOJI", "15 piece x1");
        expected.put("KESE ZA ZAMRZIVAČ DOBRO 301 4L 318458", "30 piece x1");
        expected.put("FINO KESE ZA ZAMRZIVAC 1/50 3L", "50 piece x1");
        expected.put("KESE ZA ZAMRZIVAC 1KG.50/1 LINEA", "50 piece x1");
        expected.put("Fino kese za zamrzivac 6lit/50kom", "50 piece x1");
        expected.put("KESE ZA SMEĆE AROMA T.FLEX 60L /10KOM FINO", "10 piece x1");
        expected.put("Kese za zamrzivac 1kg #50 Kafatex", "50 piece x1");
        expected.put("FINO KESE ZA DJUBRE HD 35L/30 50#", "30 piece x1");
        expected.put("Vortex kese za smeće FLEXY 70L 10", "10 piece x1");
        // A spice in a zip bag keeps its weight; food in a bag too.
        expected.put("ZACIN \"C\" 250G KESE ZIP30#", "250 g x1");
        expected.put("MLEKO 2,8% 1L KESA", "1000 ml x1");

        long chain = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('BAG_COUNT_TEST', 'Kese')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();
        for (String name : expected.keySet()) {
            // As the import stores it: the parser's reading of the name.
            var read = parser.parse(name);
            jdbcClient.sql("""
                            INSERT INTO app.retailer_product (
                                retailer_id, source_product_key, name, normalized_name,
                                quantity_value, base_unit
                            )
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)
                    .params(chain, "bag-" + name, name, normalizer.normalize(name),
                            read.map(quantity -> quantity.value()).orElse(null),
                            read.map(quantity -> quantity.unit().databaseValue()).orElse(null))
                    .update();
        }

        // Twice: the upkeep after every import reads the name the same way.
        for (int round = 0; round < 2; round++) {
            jdbcClient.sql("SELECT app.refresh_package_sizes(?)").param(chain)
                    .query((resultSet, rowNumber) -> true).single();
        }

        Map<String, String> actual = new LinkedHashMap<>();
        for (String name : expected.keySet()) {
            actual.put(name, size(chain, name));
        }
        assertThat(actual).isEqualTo(expected);
    }

    private String size(long chain, String name) {
        Map<String, Object> row = jdbcClient.sql("""
                        SELECT quantity_value, base_unit, package_count
                        FROM app.retailer_product
                        WHERE retailer_id = ? AND name = ?
                        """)
                .params(chain, name)
                .query().singleRow();
        if (row.get("quantity_value") == null) {
            return "-";
        }
        return ((BigDecimal) row.get("quantity_value")).stripTrailingZeros().toPlainString()
                + " " + row.get("base_unit") + " x" + row.get("package_count");
    }
}
