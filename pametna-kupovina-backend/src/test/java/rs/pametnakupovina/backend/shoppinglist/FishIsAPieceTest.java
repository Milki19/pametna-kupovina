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
import rs.pametnakupovina.backend.TestCallers;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "riba 4" was four 600 g packs of frozen fish, 2,4 kg for 2.628 RSD (S18
 * trial on a copy of the database, 10.11.2026). A shopper counts fish as
 * lemons: four portions of about 250 g, weighed or packed as the shop sells
 * them (V165).
 */
@SpringBootTest(properties = {
        "price-import.http.request-timeout-seconds=5",
        "price-import.minimum-snapshot-date="
})
@Testcontainers
class FishIsAPieceTest {

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

    private static final String TOKEN = "fish-is-a-piece";

    @Autowired
    private TestCallers callers;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void fourFishAreFourPortionsOfAboutTwoHundredFiftyGrams() {
        var list = shoppingListService.create(new CreateShoppingListRequest("Riba"), callers.account(TOKEN));
        shoppingListService.addPastedItems(
                list.id(),
                callers.account(TOKEN),
                new PasteShoppingListItemsRequest("""
                        riba 4
                        riba
                        riba 1 kg
                        """)
        );

        var items = shoppingListService.findById(list.id(), callers.account(TOKEN)).items();
        assertThat(items.get(0).flexibleConstraints()).satisfies(fish -> {
            assertThat(fish.targetQuantity()).isEqualByComparingTo("4");
            assertThat(fish.requiredBaseUnit()).isEqualTo("piece");
        });
        assertThat(items.get(0).quantity()).isEqualByComparingTo("1");
        assertThat(items.get(0).pieceGrams()).isEqualByComparingTo("250");
        // No count: as before, no amount.
        assertThat(items.get(1).flexibleConstraints().targetQuantity()).isNull();
        assertThat(items.get(1).pieceGrams()).isNull();
        // A weight stays a weight.
        assertThat(items.get(2).flexibleConstraints().targetQuantity()).isEqualByComparingTo("1000");
        assertThat(items.get(2).flexibleConstraints().requiredBaseUnit()).isEqualTo("g");
    }

    /** The plan weighs the pieces: 4 × 250 g, though fish is not weighed out with no amount (V137). */
    @Test
    void thePlanWeighsFourFishAsOneKilogram() {
        long fish = jdbcClient.sql("SELECT id FROM app.shopping_intent WHERE code = 'FISH'")
                .query(Long.class).single();
        assertThat(jdbcClient.sql("SELECT weighed_out FROM app.shopping_intent WHERE id = ?")
                .param(fish).query(Boolean.class).single()).isFalse();

        Map<String, Object> asked = jdbcClient.sql("""
                        SELECT target_quantity, required_base_unit, by_the_piece
                        FROM app.asked_in_shop(4, 'piece', FALSE, NULL, ?, 'riba')
                        """)
                .param(fish)
                .query().singleRow();

        assertThat((BigDecimal) asked.get("target_quantity")).isEqualByComparingTo("1000");
        assertThat(asked.get("required_base_unit")).isEqualTo("g");
        assertThat(asked.get("by_the_piece")).isEqualTo(false);
    }
}
