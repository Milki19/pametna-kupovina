package rs.pametnakupovina.backend.dataquality;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class ProductReviewService {

    private static final Set<String> REPORT_STATUSES = Set.of("NEW", "CONFIRMED", "REJECTED");

    private final JdbcClient jdbcClient;

    public ProductReviewService(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProductMergeSuggestionReview> reviewMergeSuggestions(int limit) {
        return jdbcClient.sql("""
                        SELECT suggestion.id,
                               suggestion.score,
                               brand.display_name AS brand,
                               left_family.quantity_value,
                               left_family.base_unit,
                               left_family.id AS left_id,
                               left_family.display_name AS left_name,
                               left_side.retailers AS left_retailers,
                               left_side.lowest_price AS left_lowest_price,
                               right_family.id AS right_id,
                               right_family.display_name AS right_name,
                               right_side.retailers AS right_retailers,
                               right_side.lowest_price AS right_lowest_price
                        FROM app.product_merge_suggestion AS suggestion
                        JOIN app.product_family AS left_family
                          ON left_family.id = suggestion.left_family_id
                        JOIN app.product_family AS right_family
                          ON right_family.id = suggestion.right_family_id
                        LEFT JOIN app.brand AS brand
                          ON brand.id = left_family.brand_id
                        CROSS JOIN LATERAL (
                            SELECT STRING_AGG(retailer.name, ', ' ORDER BY retailer.name) AS retailers,
                                   MIN(presence.minimum_effective_price) AS lowest_price
                            FROM app.product_retailer_presence AS presence
                            JOIN app.retailer AS retailer
                              ON retailer.id = presence.retailer_id
                            WHERE presence.product_family_id = left_family.id
                        ) AS left_side
                        CROSS JOIN LATERAL (
                            SELECT STRING_AGG(retailer.name, ', ' ORDER BY retailer.name) AS retailers,
                                   MIN(presence.minimum_effective_price) AS lowest_price
                            FROM app.product_retailer_presence AS presence
                            JOIN app.retailer AS retailer
                              ON retailer.id = presence.retailer_id
                            WHERE presence.product_family_id = right_family.id
                        ) AS right_side
                        ORDER BY suggestion.score DESC, suggestion.id
                        LIMIT ?
                        """)
                .param(1, limit)
                .query((resultSet, rowNumber) -> new ProductMergeSuggestionReview(
                        resultSet.getLong("id"),
                        resultSet.getBigDecimal("score"),
                        resultSet.getString("brand"),
                        resultSet.getBigDecimal("quantity_value"),
                        resultSet.getString("base_unit"),
                        new ProductMergeSuggestionReview.Side(
                                resultSet.getLong("left_id"),
                                resultSet.getString("left_name"),
                                resultSet.getString("left_retailers"),
                                resultSet.getBigDecimal("left_lowest_price")
                        ),
                        new ProductMergeSuggestionReview.Side(
                                resultSet.getLong("right_id"),
                                resultSet.getString("right_name"),
                                resultSet.getString("right_retailers"),
                                resultSet.getBigDecimal("right_lowest_price")
                        )
                ))
                .list();
    }

    /**
     * Kept by family key, so the decision outlives the rebuild that applies
     * it. The two become the product more chains sell.
     */
    @Transactional
    public ProductMergeDecisionResult decideMerge(long suggestionId, ProductMergeDecisionRequest request) {
        if (request == null || request.same() == null) {
            throw badRequest("Reci da li su to isti proizvod.");
        }

        MergePair pair = jdbcClient.sql("""
                        SELECT SUBSTRING(left_family.family_key FROM 4) AS left_key,
                               SUBSTRING(right_family.family_key FROM 4) AS right_key,
                               (SELECT COUNT(*) FROM app.product_retailer_presence AS presence
                                WHERE presence.product_family_id = left_family.id) AS left_chains,
                               (SELECT COUNT(*) FROM app.product_retailer_presence AS presence
                                WHERE presence.product_family_id = right_family.id) AS right_chains
                        FROM app.product_merge_suggestion AS suggestion
                        JOIN app.product_family AS left_family
                          ON left_family.id = suggestion.left_family_id
                        JOIN app.product_family AS right_family
                          ON right_family.id = suggestion.right_family_id
                        WHERE suggestion.id = ?
                        """)
                .param(1, suggestionId)
                .query((resultSet, rowNumber) -> new MergePair(
                        resultSet.getString("left_key"),
                        resultSet.getString("right_key"),
                        resultSet.getLong("left_chains"),
                        resultSet.getLong("right_chains")
                ))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Taj predlog više ne postoji."
                ));

        boolean same = request.same();
        String intoKey = pair.leftChains() >= pair.rightChains() ? pair.leftKey() : pair.rightKey();
        String firstKey = pair.leftKey().compareTo(pair.rightKey()) < 0 ? pair.leftKey() : pair.rightKey();
        String secondKey = firstKey.equals(pair.leftKey()) ? pair.rightKey() : pair.leftKey();

        jdbcClient.sql("""
                        INSERT INTO app.product_merge_decision (left_key, right_key, into_key, decision)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT (left_key, right_key) DO UPDATE SET
                            into_key = EXCLUDED.into_key,
                            decision = EXCLUDED.decision,
                            decided_at = NOW()
                        """)
                .param(1, firstKey)
                .param(2, secondKey)
                .param(3, same ? intoKey : null)
                .param(4, same ? "SAME" : "DIFFERENT")
                .update();

        jdbcClient.sql("DELETE FROM app.product_merge_suggestion WHERE id = ?")
                .param(1, suggestionId)
                .update();

        return same
                ? new ProductMergeDecisionResult(suggestionId, "SAME",
                        "Zapisano. Postaju jedan proizvod pri sledećem osvežavanju kataloga.")
                : new ProductMergeDecisionResult(suggestionId, "DIFFERENT",
                        "Zapisano. Ovaj par se više neće predlagati.");
    }

    public List<ProductReportReview> reviewReports(String status, int limit) {
        String wanted = status == null ? "NEW" : status.trim().toUpperCase();
        if (!REPORT_STATUSES.contains(wanted)) {
            throw badRequest("Status je NEW, CONFIRMED ili REJECTED.");
        }
        return jdbcClient.sql(REPORT_SELECT + """
                        WHERE report.status = ?
                        ORDER BY report.created_at DESC, report.id DESC
                        LIMIT ?
                        """)
                .param(1, wanted)
                .param(2, limit)
                .query((resultSet, rowNumber) -> readReport(resultSet))
                .list();
    }

    @Transactional
    public ProductReportReview decideReport(long reportId, ProductReportReviewRequest request) {
        String status = request == null || request.status() == null
                ? ""
                : request.status().trim().toUpperCase();
        if (!status.equals("CONFIRMED") && !status.equals("REJECTED")) {
            throw badRequest("Status je CONFIRMED ili REJECTED.");
        }
        int updated = jdbcClient.sql("""
                        UPDATE app.product_report
                        SET status = ?, reviewed_at = NOW()
                        WHERE id = ?
                        """)
                .param(1, status)
                .param(2, reportId)
                .update();
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ta prijava ne postoji.");
        }
        return jdbcClient.sql(REPORT_SELECT + "WHERE report.id = ?")
                .param(1, reportId)
                .query((resultSet, rowNumber) -> readReport(resultSet))
                .single();
    }

    private static final String REPORT_SELECT = """
            SELECT report.id,
                   report.canonical_product_id,
                   canonical.name AS product_name,
                   retailer.name AS retailer_name,
                   product.name AS listing_name,
                   report.reason,
                   report.note,
                   report.status,
                   report.created_at
            FROM app.product_report AS report
            JOIN app.canonical_product AS canonical
              ON canonical.id = report.canonical_product_id
            LEFT JOIN app.retailer_product AS product
              ON product.id = report.retailer_product_id
            LEFT JOIN app.retailer AS retailer
              ON retailer.id = product.retailer_id
            """;

    private static ProductReportReview readReport(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new ProductReportReview(
                resultSet.getLong("id"),
                resultSet.getLong("canonical_product_id"),
                resultSet.getString("product_name"),
                resultSet.getString("retailer_name"),
                resultSet.getString("listing_name"),
                resultSet.getString("reason"),
                resultSet.getString("note"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class)
        );
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record MergePair(String leftKey, String rightKey, long leftChains, long rightChains) {
    }
}
