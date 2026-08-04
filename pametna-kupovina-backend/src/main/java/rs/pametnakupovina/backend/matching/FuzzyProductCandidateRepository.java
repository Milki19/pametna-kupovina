package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class FuzzyProductCandidateRepository {

    private static final RowMapper<FuzzyProductCandidateRow>
            CANDIDATE_ROW_MAPPER = (resultSet, rowNumber) ->
            new FuzzyProductCandidateRow(
                    resultSet.getLong("canonical_product_id"),
                    resultSet.getString("name"),
                    resultSet.getString("brand"),
                    resultSet.getString("barcode"),
                    resultSet.getBigDecimal("quantity_value"),
                    resultSet.getString("base_unit"),
                    resultSet.getBigDecimal("name_similarity")
            );

    private final JdbcClient jdbcClient;

    public FuzzyProductCandidateRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<FuzzyProductCandidateRow> findByNormalizedName(
            String normalizedName,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT product.id AS canonical_product_id,
                               product.name,
                               product.brand,
                               product.barcode,
                               product.quantity_value,
                               product.base_unit,
                               ROUND(
                                   public.similarity(
                                       product.normalized_name,
                                       ?
                                   )::numeric,
                                   4
                               ) AS name_similarity
                        FROM app.canonical_product product
                        WHERE product.normalized_name IS NOT NULL
                          AND product.normalized_name
                              OPERATOR(public.%) ?
                        ORDER BY name_similarity DESC,
                                 product.name ASC,
                                 product.id ASC
                        LIMIT ?
                        """)
                .param(1, normalizedName)
                .param(2, normalizedName)
                .param(3, limit)
                .query(CANDIDATE_ROW_MAPPER)
                .list();
    }
}
