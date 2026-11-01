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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * „Izaberi drugi proizvod“ (V152): the line and both products are kept when
 * a shopper swaps the product, never for a new quantity or a typed name, and
 * never with the account.
 */
@SpringBootTest(properties = "price-import.minimum-snapshot-date=")
@Testcontainers
class ProductChoiceChangeTest {

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
    private TestCallers callers;

    @Autowired
    private ShoppingListService shoppingListService;

    @Autowired
    private ProductChoiceChangeController controller;

    @Autowired
    private JdbcClient jdbcClient;

    private long product(String key, String name) {
        return jdbcClient.sql("""
                        INSERT INTO app.canonical_product (canonical_key, name, normalized_name)
                        VALUES (?, ?, LOWER(?))
                        RETURNING id
                        """)
                .params(key, name, name)
                .query(Long.class)
                .single();
    }

    private ShoppingListItemResponse choose(long listId, long accountId, ShoppingListItemResponse item, Long product, BigDecimal quantity) {
        return shoppingListService.updateItem(listId, item.id(), accountId, new UpdateShoppingListItemRequest(
                item.name(), item.rawInput(), null, product, null, quantity,
                product == null ? ShoppingItemRule.FLEXIBLE_CATEGORY : ShoppingItemRule.EXACT_PRODUCT,
                product == null ? new FlexibleItemConstraints(item.name(), null, null, null, null) : null));
    }

    @Test
    void aSwappedProductIsKeptWithTheLineButNotWithTheAccount() {
        long account = callers.account("choice-change");
        long listId = shoppingListService.create(new CreateShoppingListRequest("Spisak"), account).id();
        ShoppingListItemResponse item = shoppingListService.addPastedItems(listId, account,
                new PasteShoppingListItemsRequest("parmezan 200g")).items().getFirst();
        long salami = product("TEST:SALAMA", "Salama sa parmezanom 100g");
        long cheese = product("TEST:PARMEZAN", "Parmigiano Reggiano 200g");

        item = choose(listId, account, item, salami, BigDecimal.ONE);
        item = choose(listId, account, item, cheese, BigDecimal.ONE);
        item = choose(listId, account, item, cheese, BigDecimal.valueOf(2));
        choose(listId, account, item, null, BigDecimal.ONE);

        var rows = jdbcClient.sql("""
                        SELECT line_text, previous_status, previous_canonical_product_id, chosen_canonical_product_id
                        FROM app.product_choice_change ORDER BY id
                        """)
                .query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("line_text", "parmezan 200g")
                .containsEntry("previous_status", "CONFIRMED")
                .containsEntry("previous_canonical_product_id", salami)
                .containsEntry("chosen_canonical_product_id", cheese);
        assertThat(jdbcClient.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'app' AND table_name = 'product_choice_change'
                        """).query(String.class).list())
                .noneMatch(column -> column.contains("account") || column.contains("token") || column.contains("device"));

        assertThat(controller.changes(7)).anySatisfy(change -> {
            assertThat(change.line()).isEqualTo("parmezan 200g");
            assertThat(change.previous()).isEqualTo("Salama sa parmezanom 100g");
            assertThat(change.chosen()).isEqualTo("Parmigiano Reggiano 200g");
            assertThat(change.times()).isEqualTo(1);
            assertThat(change.market()).isEqualTo("RS");
        });
    }
}
