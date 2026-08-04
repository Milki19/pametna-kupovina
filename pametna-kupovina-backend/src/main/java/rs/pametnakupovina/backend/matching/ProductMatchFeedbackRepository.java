package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class ProductMatchFeedbackRepository {

    private static final RowMapper<ReusableProductMatch>
            REUSABLE_MATCH_ROW_MAPPER = (resultSet, rowNumber) ->
            new ReusableProductMatch(
                    resultSet.getLong("feedback_id"),
                    resultSet.getLong("decision_id"),
                    ProductMatchFeedbackAction.valueOf(
                            resultSet.getString("action")
                    ),
                    resultSet.getObject(
                            "canonical_product_id",
                            Long.class
                    )
            );

    private final JdbcClient jdbcClient;

    public ProductMatchFeedbackRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public boolean decisionExistsForClient(
            Long decisionId,
            String clientToken
    ) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.product_match_decision
                        WHERE id = ?
                          AND (
                              client_token IS NULL
                                  OR client_token = ?
                          )
                        """)
                .param(1, decisionId)
                .param(2, clientToken)
                .query(Long.class)
                .single();

        return count > 0;
    }

    public boolean canonicalProductExists(Long canonicalProductId) {
        Long count = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.canonical_product
                        WHERE id = ?
                        """)
                .param(1, canonicalProductId)
                .query(Long.class)
                .single();

        return count > 0;
    }

    public ProductMatchFeedback save(
            Long decisionId,
            String clientToken,
            ProductMatchFeedbackAction action,
            Long selectedCanonicalProductId,
            String note
    ) {
        return jdbcClient.sql("""
                        INSERT INTO app.product_match_feedback (
                            decision_id,
                            client_token,
                            action,
                            selected_canonical_product_id,
                            note
                        )
                        VALUES (?, ?, ?, ?, ?)
                        RETURNING id, created_at
                        """)
                .param(1, decisionId)
                .param(2, clientToken)
                .param(3, action.name())
                .param(
                        4,
                        selectedCanonicalProductId,
                        Types.BIGINT
                )
                .param(5, note, Types.VARCHAR)
                .query((resultSet, rowNumber) ->
                        new ProductMatchFeedback(
                                resultSet.getLong("id"),
                                decisionId,
                                action,
                                selectedCanonicalProductId,
                                true,
                                resultSet.getObject(
                                        "created_at",
                                        OffsetDateTime.class
                                ).toInstant()
                        )
                )
                .single();
    }

    public Optional<ReusableProductMatch> findReusableFeedback(
            String clientToken,
            String normalizedQuery
    ) {
        return jdbcClient.sql("""
                        SELECT latest.feedback_id,
                               latest.decision_id,
                               latest.action,
                               latest.canonical_product_id
                        FROM (
                            SELECT feedback.id AS feedback_id,
                                   decision.id AS decision_id,
                                   feedback.action,
                                   feedback.selected_canonical_product_id
                                       AS canonical_product_id,
                                   feedback.created_at
                            FROM app.product_match_feedback feedback
                            JOIN app.product_match_decision decision
                              ON decision.id = feedback.decision_id
                            WHERE feedback.client_token = ?
                              AND decision.normalized_query = ?
                            ORDER BY feedback.created_at DESC,
                                     feedback.id DESC
                            LIMIT 1
                        ) latest
                        """)
                .param(1, clientToken)
                .param(2, normalizedQuery)
                .query(REUSABLE_MATCH_ROW_MAPPER)
                .optional();
    }
}
