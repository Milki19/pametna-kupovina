package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;

@Repository
public class CanonicalProductSearchRepository {

    private static final RowMapper<CanonicalProductSearchRow>
            SEARCH_ROW_MAPPER = (resultSet, rowNumber) ->
            new CanonicalProductSearchRow(
                    resultSet.getLong("product_family_id"),
                    resultSet.getObject(
                            "canonical_product_id",
                            Long.class
                    ),
                    resultSet.getString("name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity_value"),
                    resultSet.getString("base_unit"),
                    resultSet.getString("category_code"),
                    resultSet.getString("category_name"),
                    resultSet.getInt("variant_count"),
                    resultSet.getBigDecimal("name_similarity"),
                    resultSet.getBoolean("exact_ean_match")
            );

    private static final RowMapper<ProductAvailabilityRow>
            AVAILABILITY_ROW_MAPPER = (resultSet, rowNumber) ->
            new ProductAvailabilityRow(
                    resultSet.getLong("product_family_id"),
                    new ProductRetailerAvailability(
                            resultSet.getString("retailer_code"),
                            resultSet.getString("retailer_name"),
                            resultSet.getObject(
                                    "latest_price_date",
                                    LocalDate.class
                            ),
                            resultSet.getInt("store_count"),
                            resultSet.getInt("format_count"),
                            resultSet.getBigDecimal(
                                    "minimum_effective_price"
                            )
                    )
            );

    private final JdbcClient jdbcClient;

    public CanonicalProductSearchRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<CanonicalProductSearchRow> findCandidates(
            String normalizedQuery,
            String validEan
    ) {
        return jdbcClient.sql("""
                        SELECT family.id AS product_family_id,
                               representative.id AS canonical_product_id,
                               family.display_name AS name,
                               brand.display_name AS brand,
                               representative.barcode,
                               family.quantity_value,
                               family.base_unit,
                               category.code AS category_code,
                               category.name AS category_name,
                               GREATEST(
                                   1,
                                   (
                                       SELECT COUNT(*)
                                       FROM app.product_family_member AS member
                                       WHERE member.family_id = family.id
                                   )
                               )::INTEGER AS variant_count,
                               CASE
                                   WHEN ? IS NOT NULL
                                       AND EXISTS (
                                           SELECT 1
                                           FROM app.product_family_member AS member
                                           JOIN app.canonical_product AS canonical
                                             ON canonical.id =
                                                 member.canonical_product_id
                                           WHERE member.family_id = family.id
                                             AND canonical.barcode = ?
                                       )
                                       THEN 1.0000::NUMERIC
                                   ELSE ROUND(
                                       public.similarity(
                                           family.normalized_name,
                                           ?
                                       )::NUMERIC,
                                       4
                                   )
                               END AS name_similarity,
                               (
                                   ? IS NOT NULL
                                   AND EXISTS (
                                       SELECT 1
                                       FROM app.product_family_member AS member
                                       JOIN app.canonical_product AS canonical
                                         ON canonical.id =
                                             member.canonical_product_id
                                       WHERE member.family_id = family.id
                                         AND canonical.barcode = ?
                                   )
                               ) AS exact_ean_match
                        FROM app.product_family AS family
                        LEFT JOIN app.brand AS brand
                          ON brand.id = family.brand_id
                        LEFT JOIN app.product_category AS category
                          ON category.id = family.product_category_id
                        LEFT JOIN LATERAL (
                            SELECT canonical.id,
                                   canonical.barcode
                            FROM app.product_family_member AS member
                            JOIN app.canonical_product AS canonical
                              ON canonical.id = member.canonical_product_id
                            WHERE member.family_id = family.id
                            ORDER BY CASE
                                         WHEN ? IS NOT NULL
                                             AND canonical.barcode = ?
                                             THEN 1
                                         ELSE 2
                                     END,
                                     EXISTS (
                                         SELECT 1
                                         FROM app.retailer_product AS product
                                         JOIN app.current_price_offer AS offer
                                           ON offer.retailer_product_id =
                                               product.id
                                         WHERE product.canonical_product_id =
                                             canonical.id
                                     ) DESC,
                                     canonical.id
                            LIMIT 1
                        ) AS representative ON TRUE
                        WHERE family.review_status <> 'REJECTED'
                          AND (
                              (
                                  ? IS NOT NULL
                                  AND EXISTS (
                                      SELECT 1
                                      FROM app.product_family_member AS member
                                      JOIN app.canonical_product AS canonical
                                        ON canonical.id =
                                            member.canonical_product_id
                                      WHERE member.family_id = family.id
                                        AND canonical.barcode = ?
                                  )
                              )
                              OR family.normalized_name
                                  OPERATOR(public.%) ?
                              OR family.normalized_name LIKE
                                  '%' || ? || '%'
                          )
                        ORDER BY name_similarity DESC,
                                 family.display_name ASC,
                                 family.id ASC
                        """)
                .param(1, validEan, Types.VARCHAR)
                .param(2, validEan, Types.VARCHAR)
                .param(3, normalizedQuery)
                .param(4, validEan, Types.VARCHAR)
                .param(5, validEan, Types.VARCHAR)
                .param(6, validEan, Types.VARCHAR)
                .param(7, validEan, Types.VARCHAR)
                .param(8, validEan, Types.VARCHAR)
                .param(9, validEan, Types.VARCHAR)
                .param(10, normalizedQuery)
                .param(11, normalizedQuery)
                .query(SEARCH_ROW_MAPPER)
                .list();
    }

    public List<ProductAvailabilityRow> findAvailability(
            List<Long> productFamilyIds
    ) {
        if (productFamilyIds.isEmpty()) {
            return List.of();
        }

        return jdbcClient.sql("""
                        SELECT presence.product_family_id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               presence.latest_price_date,
                               presence.store_count,
                               presence.format_count,
                               presence.minimum_effective_price
                        FROM app.product_retailer_presence AS presence
                        JOIN app.retailer AS retailer
                          ON retailer.id = presence.retailer_id
                        WHERE presence.product_family_id IN (:familyIds)
                        ORDER BY presence.product_family_id,
                                 retailer.name,
                                 retailer.code
                        """)
                .param("familyIds", productFamilyIds)
                .query(AVAILABILITY_ROW_MAPPER)
                .list();
    }

    record ProductAvailabilityRow(
            Long productFamilyId,
            ProductRetailerAvailability availability
    ) {
    }
}
