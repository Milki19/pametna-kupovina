package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Everyday intents rank their usual pack first; loose goods have none. */
@SpringBootTest
@Testcontainers
class IntentDefaultSizesTest {

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

    private Map<String, Object> usualSize(String code) {
        return jdbcClient.sql("""
                        SELECT default_base_unit, default_min_package_quantity, default_max_package_quantity
                        FROM app.shopping_intent
                        WHERE code = ?
                        """)
                .param(code)
                .query()
                .singleRow();
    }

    @Test
    void riceAndWaterMeanTheUsualPack() {
        Map<String, Object> rice = usualSize("RICE");
        assertThat(rice.get("default_base_unit")).isEqualTo("g");
        assertThat((BigDecimal) rice.get("default_min_package_quantity")).isEqualByComparingTo("800");
        assertThat((BigDecimal) rice.get("default_max_package_quantity")).isEqualByComparingTo("1000");

        Map<String, Object> water = usualSize("WATER");
        assertThat(water.get("default_base_unit")).isEqualTo("ml");
        assertThat((BigDecimal) water.get("default_min_package_quantity")).isEqualByComparingTo("1500");

        // Existing defaults are not touched.
        assertThat((BigDecimal) usualSize("MILK").get("default_min_package_quantity")).isEqualByComparingTo("900");
    }

    @Test
    void onlyLooseGoodsAreLeftWithoutAUsualSize() {
        List<String> withoutSize = jdbcClient.sql("""
                        SELECT code
                        FROM app.shopping_intent
                        WHERE active
                          AND default_min_package_quantity IS NULL
                          AND default_max_package_quantity IS NULL
                        ORDER BY code
                        """)
                .query(String.class)
                .list();
        assertThat(withoutSize).containsExactly(
                "BAKERY_ROLL", "FISH", "FRESH_FISH", "FRESH_MEAT", "FRUIT", "MEAT", "VEGETABLE");
    }
}
