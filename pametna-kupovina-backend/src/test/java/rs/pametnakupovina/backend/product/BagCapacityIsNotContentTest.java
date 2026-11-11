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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "FINO KESE ZAMRZIVAC 1kg" is a bag that holds a kilogram, not a kilogram of
 * goods, and "KESE ZA SMEĆE 60L 10KOM" are ten bags, not 60 litres. A bag is
 * counted in pieces; one whose name says no count has no size. Food in a bag
 * keeps its weight (V166).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class BagCapacityIsNotContentTest {

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
    void aBagIsCountedAndFoodInABagKeepsItsWeight() {
        long chain = jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES ('BAG_TEST', 'Kese')
                        RETURNING id
                        """)
                .query(Long.class)
                .single();

        String[] names = {
                "FINO KESE ZAMRZIVAC 1kg",
                "KESE ZA ZAMRZIVAČ ZIPER FINO 1L 20/1",
                "KESE ZA SMEĆE TYTAN FLEX 60L 10KOM FINO",
                "FINO KESE ZA DJ. REC 15/1 35L",
                "Vreće za đubre 120L 10 komada",
                "MLEKO 2,8% 1L KESA",
                "GRAŠAK ZA ZAMRZIVAČ KESA 1KG",
                "FRISKIES GOVEDINA KESICA 85G"
        };
        for (String name : names) {
            // As the import stores it: the parser's reading of the name.
            var read = parser.parse(name).orElseThrow();
            jdbcClient.sql("""
                            INSERT INTO app.retailer_product (
                                retailer_id, source_product_key, name, normalized_name,
                                quantity_value, base_unit
                            )
                            VALUES (?, ?, ?, ?, ?, ?)
                            """)
                    .params(chain, "bag-" + name, name, normalizer.normalize(name),
                            read.value(), read.unit().databaseValue())
                    .update();
        }

        // Twice: an import puts the parser's reading back, and the upkeep
        // reads the name again with the same result.
        jdbcClient.sql("SELECT app.refresh_package_sizes(?)").param(chain)
                .query((resultSet, rowNumber) -> true).single();
        jdbcClient.sql("SELECT app.refresh_package_sizes(?)").param(chain)
                .query((resultSet, rowNumber) -> true).single();

        assertThat(size(chain, "FINO KESE ZAMRZIVAC 1kg")).isEqualTo("-");
        assertThat(size(chain, "KESE ZA ZAMRZIVAČ ZIPER FINO 1L 20/1")).isEqualTo("20 piece x1");
        assertThat(size(chain, "KESE ZA SMEĆE TYTAN FLEX 60L 10KOM FINO")).isEqualTo("10 piece x1");
        assertThat(size(chain, "FINO KESE ZA DJ. REC 15/1 35L")).isEqualTo("15 piece x1");
        assertThat(size(chain, "Vreće za đubre 120L 10 komada")).isEqualTo("10 piece x1");
        assertThat(size(chain, "MLEKO 2,8% 1L KESA")).isEqualTo("1000 ml x1");
        assertThat(size(chain, "GRAŠAK ZA ZAMRZIVAČ KESA 1KG")).isEqualTo("1000 g x1");
        assertThat(size(chain, "FRISKIES GOVEDINA KESICA 85G")).isEqualTo("85 g x1");
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
