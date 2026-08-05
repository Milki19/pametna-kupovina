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
            ITEM_ROW_MAPPER = (resultSet, rowNumber) -> {
        ShoppingItemRule matchingRule = ShoppingItemRule.valueOf(
                resultSet.getString("matching_rule")
        );

        FlexibleItemConstraints flexibleConstraints =
                matchingRule == ShoppingItemRule.FLEXIBLE_CATEGORY
                        ? new FlexibleItemConstraints(
                        resultSet.getString("flexible_category"),
                        resultSet.getString("required_brand"),
                        resultSet.getBigDecimal(
                                "min_package_quantity"
                        ),
                        resultSet.getBigDecimal(
                                "max_package_quantity"
                        ),
                        resultSet.getString("required_base_unit")
                )
                        : null;

        return new ShoppingListItemResponse(
                    resultSet.getLong("id"),
                    resultSet.getString("name"),
                    resultSet.getString("raw_input"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity"),
                    matchingRule,
                    ShoppingItemMatchingStatus.valueOf(
                            resultSet.getString("matching_status")
                    ),
                    resultSet.getObject(
                            "matched_canonical_product_id",
                            Long.class
                    ),
                    resultSet.getObject(
                            "matching_decision_id",
                            Long.class
                    ),
                    resultSet.getBigDecimal("matching_score"),
                    resultSet.getString(
                            "matching_algorithm_version"
                    ),
                    flexibleConstraints,
                    resultSet.getObject(
                            "created_at",
                            OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "updated_at",
                            OffsetDateTime.class
                    )
            );
    };

    private final JdbcClient jdbcClient;

    public ShoppingListRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public ShoppingListSummary create(
            String name,
            String clientTokenHash
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.shopping_list(
                            name,
                            client_token_hash
                        )
                        VALUES (?, ?)
                        RETURNING id,
                                  name,
                                  created_at,
                                  updated_at,
                                  0 AS item_count
                        """)
                .param(1, name)
                .param(2, clientTokenHash)
                .query(SUMMARY_ROW_MAPPER)
                .single();
    }

    public List<ShoppingListSummary> findAll(
            String clientTokenHash
    ) {
        return jdbcClient.sql("""
                        SELECT sl.id,
                               sl.name,
                               sl.created_at,
                               sl.updated_at,
                               COUNT(sli.id)::INTEGER AS item_count
                        FROM app.shopping_list sl
                        LEFT JOIN app.shopping_list_item sli
                          ON sli.shopping_list_id = sl.id
                        WHERE sl.client_token_hash = ?
                          AND sl.active = TRUE
                        GROUP BY sl.id,
                                 sl.name,
                                 sl.created_at,
                                 sl.updated_at
                        ORDER BY sl.updated_at DESC,
                                 sl.id DESC
                        """)
                .param(1, clientTokenHash)
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
                                  AND active = TRUE
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

        return responseForHeader(header, listId);
    }

    public Optional<ShoppingListResponse> findById(
            Long listId,
            String clientTokenHash
    ) {
        Optional<ShoppingListHeader> header =
                jdbcClient.sql("""
                                SELECT id,
                                       name,
                                       created_at,
                                       updated_at
                                FROM app.shopping_list
                                WHERE id = ?
                                  AND client_token_hash = ?
                                  AND active = TRUE
                                """)
                        .param(1, listId)
                        .param(2, clientTokenHash)
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

        return responseForHeader(header, listId);
    }

    private Optional<ShoppingListResponse> responseForHeader(
            Optional<ShoppingListHeader> header,
            Long listId
    ) {
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
                                       matching_decision_id,
                                       matching_score,
                                       matching_algorithm_version,
                                       flexible_category,
                                       required_brand,
                                       min_package_quantity,
                                       max_package_quantity,
                                       required_base_unit,
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

    public boolean existsByIdAndClientTokenHash(
            Long listId,
            String clientTokenHash
    ) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.shopping_list
                            WHERE id = ?
                              AND client_token_hash = ?
                              AND active = TRUE
                        )
                        """)
                .param(1, listId)
                .param(2, clientTokenHash)
                .query(Boolean.class)
                .single();
    }

    public boolean updateName(
            Long listId,
            String clientTokenHash,
            String name
    ) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE app.shopping_list
                        SET name = ?,
                            updated_at = NOW()
                        WHERE id = ?
                          AND client_token_hash = ?
                          AND active = TRUE
                        """)
                .param(1, name)
                .param(2, listId)
                .param(3, clientTokenHash)
                .update();

        return updatedRows > 0;
    }

    public ShoppingListItemResponse addItem(
            Long listId,
            String name,
            String rawInput,
            String barcode,
            java.math.BigDecimal quantity,
            ShoppingItemRule matchingRule,
            String flexibleCategory,
            String flexibleCategoryNormalized,
            String requiredBrand,
            java.math.BigDecimal minPackageQuantity,
            java.math.BigDecimal maxPackageQuantity,
            String requiredBaseUnit
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
                            matched_canonical_product_id,
                            flexible_category,
                            flexible_category_normalized,
                            required_brand,
                            min_package_quantity,
                            max_package_quantity,
                            required_base_unit
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id,
                                  name,
                                  raw_input,
                                  barcode,
                                  quantity,
                                  matching_rule,
                                  matching_status,
                                  matched_canonical_product_id,
                                  matching_decision_id,
                                  matching_score,
                                  matching_algorithm_version,
                                  flexible_category,
                                  required_brand,
                                  min_package_quantity,
                                  max_package_quantity,
                                  required_base_unit,
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
                .param(9, flexibleCategory, Types.VARCHAR)
                .param(
                        10,
                        flexibleCategoryNormalized,
                        Types.VARCHAR
                )
                .param(11, requiredBrand, Types.VARCHAR)
                .param(12, minPackageQuantity, Types.NUMERIC)
                .param(13, maxPackageQuantity, Types.NUMERIC)
                .param(14, requiredBaseUnit, Types.VARCHAR)
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

    public boolean deactivateList(
            Long listId,
            String clientTokenHash
    ) {
        int updatedRows = jdbcClient.sql("""
                        UPDATE app.shopping_list
                        SET active = FALSE,
                            updated_at = NOW()
                        WHERE id = ?
                          AND client_token_hash = ?
                          AND active = TRUE
                        """)
                .param(1, listId)
                .param(2, clientTokenHash)
                .update();

        return updatedRows > 0;
    }

    public void touch(Long listId) {
        jdbcClient.sql("""
                        UPDATE app.shopping_list
                        SET updated_at = NOW()
                        WHERE id = ?
                          AND active = TRUE
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
            ShoppingItemRule matchingRule,
            String flexibleCategory,
            String flexibleCategoryNormalized,
            String requiredBrand,
            java.math.BigDecimal minPackageQuantity,
            java.math.BigDecimal maxPackageQuantity,
            String requiredBaseUnit
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
                        matching_decision_id = NULL,
                        matching_score = NULL,
                        matching_algorithm_version = NULL,
                        flexible_category = ?,
                        flexible_category_normalized = ?,
                        required_brand = ?,
                        min_package_quantity = ?,
                        max_package_quantity = ?,
                        required_base_unit = ?,
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
                              matching_decision_id,
                              matching_score,
                              matching_algorithm_version,
                              flexible_category,
                              required_brand,
                              min_package_quantity,
                              max_package_quantity,
                              required_base_unit,
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
                .param(8, flexibleCategory, Types.VARCHAR)
                .param(
                        9,
                        flexibleCategoryNormalized,
                        Types.VARCHAR
                )
                .param(10, requiredBrand, Types.VARCHAR)
                .param(11, minPackageQuantity, Types.NUMERIC)
                .param(12, maxPackageQuantity, Types.NUMERIC)
                .param(13, requiredBaseUnit, Types.VARCHAR)
                .param(14, itemId)
                .param(15, listId)
                .query(ITEM_ROW_MAPPER)
                .optional();
    }

    public Optional<ShoppingListItemResponse> findItemById(
            Long listId,
            Long itemId
    ) {
        return jdbcClient.sql("""
                        SELECT id,
                               name,
                               raw_input,
                               barcode,
                               quantity,
                               matching_rule,
                               matching_status,
                               matched_canonical_product_id,
                               matching_decision_id,
                               matching_score,
                               matching_algorithm_version,
                               flexible_category,
                               required_brand,
                               min_package_quantity,
                               max_package_quantity,
                               required_base_unit,
                               created_at,
                               updated_at
                        FROM app.shopping_list_item
                        WHERE shopping_list_id = ?
                          AND id = ?
                        """)
                .param(1, listId)
                .param(2, itemId)
                .query(ITEM_ROW_MAPPER)
                .optional();
    }

    public Optional<ShoppingListItemResponse> updateMatchingResult(
            Long listId,
            Long itemId,
            ShoppingItemMatchingStatus status,
            Long matchedCanonicalProductId,
            Long matchingDecisionId,
            java.math.BigDecimal matchingScore,
            String matchingAlgorithmVersion
    ) {
        return jdbcClient.sql("""
                        UPDATE app.shopping_list_item
                        SET matching_status = ?,
                            matched_canonical_product_id = ?,
                            matching_decision_id = ?,
                            matching_score = ?,
                            matching_algorithm_version = ?,
                            updated_at = NOW()
                        WHERE shopping_list_id = ?
                          AND id = ?
                        RETURNING id,
                                  name,
                                  raw_input,
                                  barcode,
                                  quantity,
                                  matching_rule,
                                  matching_status,
                                  matched_canonical_product_id,
                                  matching_decision_id,
                                  matching_score,
                                  matching_algorithm_version,
                                  flexible_category,
                                  required_brand,
                                  min_package_quantity,
                                  max_package_quantity,
                                  required_base_unit,
                                  created_at,
                                  updated_at
                        """)
                .param(1, status.name())
                .param(
                        2,
                        matchedCanonicalProductId,
                        Types.BIGINT
                )
                .param(3, matchingDecisionId, Types.BIGINT)
                .param(4, matchingScore, Types.NUMERIC)
                .param(
                        5,
                        matchingAlgorithmVersion,
                        Types.VARCHAR
                )
                .param(6, listId)
                .param(7, itemId)
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
