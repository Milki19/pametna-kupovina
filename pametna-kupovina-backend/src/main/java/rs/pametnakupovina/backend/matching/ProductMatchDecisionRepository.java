package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;

@Repository
public class ProductMatchDecisionRepository {

    private final JdbcClient jdbcClient;

    public ProductMatchDecisionRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Long save(
            String rawQuery,
            String normalizedQuery,
            Long topCandidateId,
            Long topCandidateFamilyId,
            Long matchedCanonicalProductId,
            BigDecimal score,
            ProductMatchStatus status,
            String algorithmVersion,
            String clientToken
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.product_match_decision (
                            raw_query,
                            normalized_query,
                            top_candidate_id,
                            top_candidate_family_id,
                            matched_canonical_product_id,
                            score,
                            status,
                            algorithm_version,
                            client_token
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)
                .param(1, rawQuery)
                .param(2, normalizedQuery)
                .param(3, topCandidateId, Types.BIGINT)
                .param(4, topCandidateFamilyId, Types.BIGINT)
                .param(
                        5,
                        matchedCanonicalProductId,
                        Types.BIGINT
                )
                .param(6, score)
                .param(7, status.name())
                .param(8, algorithmVersion)
                .param(9, clientToken, Types.VARCHAR)
                .query(Long.class)
                .single();
    }
}
