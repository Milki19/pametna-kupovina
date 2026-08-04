package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class ShoppingListRepository {

    private static final RowMapper<ShoppingListSummary>
            SUMMARY_ROW_MAPPER = (resultSet, rowNumber) ->
            new ShoppingListSummary(
                    resultSet.getLong("id"),
                    resultSet.getString("name"),
                    resultSet.getObject(
                            "created_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "updated_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getInt("item_count")
            );

    private static final RowMapper<ShoppingListItemResponse>
            ITEM_ROW_MAPPER = (resultSet, rowNumber) ->
            new ShoppingListItemResponse(
                    resultSet.getLong("id"),
                    resultSet.getString("name"),
                    resultSet.getString("raw_input"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity"),
                    ShoppingItemRule.valueOf(
                            resultSet.getString("matching_rule")
                    ),
                    ShoppingItemMatchingStatus.valueOf(
                            resultSet.getString("matching_status")
                    ),
                    resultSet.getObject(
                            "matched_canonical_product_id",
                            Long.class
                    ),
                    resultSet.getObject(
                            "created_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "updated_at",
                            OffsetDateTime.class
                    )
            );

    private final JdbcClient jdbcClient;

    public ShoppingListRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ShoppingListSummary create(String name) {
        return jdbcClient.sql("""
                        INSERT INTO app.shopping_list(name)
                        VALUES (?)
                        RETURNING id,
                                  name,
                                  created_at,
                                  updated_at,
                                  0 AS item_count
                        """)
                .param(1, name)
                .query(SUMMARY_ROW_MAPPER)
                .single();
    }

    public List<ShoppingListSummary> findAll() {
        return jdbcClient.sql("""
                        SELECT sl.id,
                               sl.name,
                               sl.created_at,
                               sl.updated_at,
                               COUNT(sli.id)::INTEGER AS item_count
                        FROM app.shopping_list sl
                        LEFT JOIN app.shopping_list_item sli
                          ON sli.shopping_list_id = sl.id
                        GROUP BY sl.id,
                                 sl.name,
                                 sl.created_at,
                                 sl.updated_at
                        ORDER BY sl.updated_at DESC,
                                 sl.id DESC
                        """)
                .query(SUMMARY_ROW_MAPPER)
                .list();
    }

    public Optional<ShoppingListResponse> findById(Long listId) {
        Optional<ShoppingListHeader> header =
                jdbcClient.sql("""
                                SELECT id,
                                       name,
                                       created_at,
                                       updated_at
                                FROM app.shopping_list
                                WHERE id = ?
                                """)
                        .param(1, listId)
                        .query((resultSet, rowNumber) ->
                                new ShoppingListHeader(
                                        resultSet.getLong("id"),
                                        resultSet.getString("name"),
                                        resultSet.getObject(
                                                "created_at",
                                                OffsetDateTime.class
                                        ),
                                        resultSet.getObject(
                                                "updated_at",
                                                OffsetDateTime.class
                                        )
                                ))
                        .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<ShoppingListItemResponse> items =
                jdbcClient.sql("""
                                SELECT id,
                                       name,
                                       raw_input,
                                       barcode,
                                       quantity,
                                       matching_rule,
                                       matching_status,
                                       matched_canonical_product_id,
                                       created_at,
                                       updated_at
                                FROM app.shopping_list_item
                                WHERE shopping_list_id = ?
                                ORDER BY created_at ASC,
                                         id ASC
                                """)
                        .param(1, listId)
                        .query(ITEM_ROW_MAPPER)
                        .list();

        ShoppingListHeader value = header.get();

        return Optional.of(
                new ShoppingListResponse(
                        value.id(),
                        value.name(),
                        value.createdAt(),
                        value.updatedAt(),
                        items
                )
        );
    }

    public boolean existsById(Long listId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.shopping_list
                            WHERE id = ?
                        )
                        """)
                .param(1, listId)
                .query(Boolean.class)
                .single();
    }

    public ShoppingListItemResponse addItem(
            Long listId,
            String name,
            String rawInput,
            String barcode,
            java.math.BigDecimal quantity,
            ShoppingItemRule matchingRule
    ) {
        Long matchedCanonicalProductId =
                findCanonicalProductIdByBarcode(barcode)
                        .orElse(null);

        ShoppingItemMatchingStatus matchingStatus =
                matchedCanonicalProductId == null
                        ? ShoppingItemMatchingStatus.PENDING
                        : ShoppingItemMatchingStatus.CONFIRMED;

        return jdbcClient.sql("""
                        INSERT INTO app.shopping_list_item (
                            shopping_list_id,
                            name,
                            raw_input,
                            barcode,
                            quantity,
                            matching_rule,
                            matching_status,
                            matched_canonical_product_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id,
                                  name,
                                  raw_input,
                                  barcode,
                                  quantity,
                                  matching_rule,
                                  matching_status,
                                  matched_canonical_product_id,
                                  created_at,
                                  updated_at
                        """)
                .param(1, listId)
                .param(2, name)
                .param(3, rawInput)
                .param(4, barcode, Types.VARCHAR)
                .param(5, quantity, Types.NUMERIC)
                .param(6, matchingRule.name())
                .param(7, matchingStatus.name())
                .param(
                        8,
                        matchedCanonicalProductId,
                        Types.BIGINT
                )
                .query(ITEM_ROW_MAPPER)
                .single();
    }

    public boolean deleteItem(Long listId, Long itemId) {
        int deletedRows = jdbcClient.sql("""
                        DELETE FROM app.shopping_list_item
                        WHERE id = ?
                          AND shopping_list_id = ?
                        """)
                .param(1, itemId)
                .param(2, listId)
                .update();

        return deletedRows > 0;
    }

    public boolean deleteList(Long listId) {
        int deletedRows = jdbcClient.sql("""
                        DELETE FROM app.shopping_list
                        WHERE id = ?
                        """)
                .param(1, listId)
                .update();

        return deletedRows > 0;
    }

    public void touch(Long listId) {
        jdbcClient.sql("""
                        UPDATE app.shopping_list
                        SET updated_at = NOW()
                        WHERE id = ?
                        """)
                .param(1, listId)
                .update();
    }

    public Optional<ShoppingListItemResponse> updateItem(
            Long listId,
            Long itemId,
            String name,
            String rawInput,
            String barcode,
            java.math.BigDecimal quantity,
            ShoppingItemRule matchingRule
    ) {
        Long matchedCanonicalProductId =
                findCanonicalProductIdByBarcode(barcode)
                        .orElse(null);

        ShoppingItemMatchingStatus matchingStatus =
                matchedCanonicalProductId == null
                        ? ShoppingItemMatchingStatus.PENDING
                        : ShoppingItemMatchingStatus.CONFIRMED;

        return jdbcClient.sql("""
                    UPDATE app.shopping_list_item
                    SET name = ?,
                        raw_input = ?,
                        barcode = ?,
                        quantity = ?,
                        matching_rule = ?,
                        matching_status = ?,
                        matched_canonical_product_id = ?,
                        updated_at = NOW()
                    WHERE id = ?
                      AND shopping_list_id = ?
                    RETURNING id,
                              name,
                              raw_input,
                              barcode,
                              quantity,
                              matching_rule,
                              matching_status,
                              matched_canonical_product_id,
                              created_at,
                              updated_at
                    """)
                .param(1, name)
                .param(2, rawInput)
                .param(3, barcode, Types.VARCHAR)
                .param(4, quantity, Types.NUMERIC)
                .param(5, matchingRule.name())
                .param(6, matchingStatus.name())
                .param(
                        7,
                        matchedCanonicalProductId,
                        Types.BIGINT
                )
                .param(8, itemId)
                .param(9, listId)
                .query(ITEM_ROW_MAPPER)
                .optional();
    }

    private Optional<Long> findCanonicalProductIdByBarcode(
            String barcode
    ) {
        if (barcode == null) {
            return Optional.empty();
        }

        return jdbcClient.sql("""
                        SELECT id
                        FROM app.canonical_product
                        WHERE barcode = ?
                        """)
                .param(1, barcode)
                .query(Long.class)
                .optional();
    }

    private record ShoppingListHeader(
            Long id,
            String name,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }
}
