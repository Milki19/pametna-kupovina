package rs.pametnakupovina.backend.product;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

@Repository
public class CanonicalProductSearchRepository {

    private static final RowMapper<CanonicalProductSearchRow>
            SEARCH_ROW_MAPPER = (resultSet, rowNumber) ->
            new CanonicalProductSearchRow(
                    resultSet.getLong("canonical_product_id"),
                    resultSet.getString("name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity_value"),
                    resultSet.getString("base_unit"),
                    resultSet.getBigDecimal("name_similarity"),
                    resultSet.getBoolean("exact_ean_match")
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
                        SELECT product.id AS canonical_product_id,
                               product.name,
                               product.brand,
                               product.barcode,
                               product.quantity_value,
                               product.base_unit,
                               CASE
                                   WHEN ? IS NOT NULL
                                       AND product.barcode = ?
                                       THEN 1.0000::numeric
                                   ELSE ROUND(
                                       public.similarity(
                                           product.normalized_name,
                                           ?
                                       )::numeric,
                                       4
                                   )
                               END AS name_similarity,
                               (
                                   ? IS NOT NULL
                                       AND product.barcode = ?
                               ) AS exact_ean_match
                        FROM app.canonical_product product
                        WHERE (
                            ? IS NOT NULL
                                AND product.barcode = ?
                        )
                           OR (
                               product.normalized_name IS NOT NULL
                                   AND (
                                       product.normalized_name
                                           OPERATOR(public.%) ?
                                       OR product.normalized_name LIKE
                                           '%' || ? || '%'
                                   )
                           )
                        ORDER BY name_similarity DESC,
                                 product.name ASC,
                                 product.id ASC
                        """)
                .param(1, validEan, Types.VARCHAR)
                .param(2, validEan, Types.VARCHAR)
                .param(3, normalizedQuery)
                .param(4, validEan, Types.VARCHAR)
                .param(5, validEan, Types.VARCHAR)
                .param(6, validEan, Types.VARCHAR)
                .param(7, validEan, Types.VARCHAR)
                .param(8, normalizedQuery)
                .param(9, normalizedQuery)
                .query(SEARCH_ROW_MAPPER)
                .list();
    }
}
