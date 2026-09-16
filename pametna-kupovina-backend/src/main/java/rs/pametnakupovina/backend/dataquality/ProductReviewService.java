package rs.pametnakupovina.backend.dataquality;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
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

    /** Products of one type, to find the ones that are not that type. */
    public List<ProductTypeAssignmentReview> reviewTypeAssignments(String typeCode, String query, int limit) {
        String code = requireTypeCode(typeCode);
        String search = query == null || query.isBlank() ? null : query.trim();
        return jdbcClient.sql("""
                        SELECT product.id AS retailer_product_id,
                               retailer.name AS retailer_name,
                               product.name AS product_name,
                               category.name AS category_name,
                               assignment.assignment_source,
                               assignment.confidence,
                               assignment.reviewed
                        FROM app.retailer_product_type AS assignment
                        JOIN app.product_type AS type
                          ON type.id = assignment.product_type_id
                        JOIN app.retailer_product AS product
                          ON product.id = assignment.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        LEFT JOIN LATERAL (
                            SELECT product_category.name
                            FROM app.retailer_product_category AS category_assignment
                            JOIN app.product_category AS product_category
                              ON product_category.id = category_assignment.product_category_id
                            WHERE category_assignment.retailer_product_id = product.id
                            LIMIT 1
                        ) AS category ON TRUE
                        WHERE type.code = :typeCode
                          AND (
                              CAST(:search AS TEXT) IS NULL
                              OR product.normalized_name LIKE
                                  '%' || app.fold_match_text(CAST(:search AS TEXT)) || '%'
                          )
                        ORDER BY assignment.reviewed, product.name, product.id
                        LIMIT :limit
                        """)
                .param("typeCode", code)
                .param("search", search)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new ProductTypeAssignmentReview(
                        resultSet.getLong("retailer_product_id"),
                        resultSet.getString("retailer_name"),
                        resultSet.getString("product_name"),
                        resultSet.getString("category_name"),
                        resultSet.getString("assignment_source"),
                        resultSet.getBigDecimal("confidence"),
                        resultSet.getBoolean("reviewed")
                ))
                .list();
    }

    /**
     * Sheba cat food is not fish. The type goes, and a later catalogue
     * refresh does not give it back (V83).
     */
    @Transactional
    public ProductTypeRejectionResult rejectTypeAssignment(long retailerProductId, ProductTypeRejectionRequest request) {
        String code = requireTypeCode(request == null ? null : request.productTypeCode());
        long typeId = jdbcClient.sql("""
                        DELETE FROM app.retailer_product_type AS assignment
                        USING app.product_type AS type
                        WHERE assignment.retailer_product_id = ?
                          AND assignment.product_type_id = type.id
                          AND type.code = ?
                        RETURNING type.id
                        """)
                .param(1, retailerProductId)
                .param(2, code)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Taj proizvod nema tu vrstu."
                ));

        jdbcClient.sql("""
                        INSERT INTO app.retailer_product_type_rejection (retailer_product_id, product_type_id)
                        VALUES (?, ?)
                        ON CONFLICT DO NOTHING
                        """)
                .param(1, retailerProductId)
                .param(2, typeId)
                .update();

        jdbcClient.sql("""
                        UPDATE app.product_type_candidate
                        SET status = 'REJECTED',
                            reviewed_at = NOW(),
                            updated_at = NOW()
                        WHERE retailer_product_id = ?
                          AND product_type_id = ?
                          AND status = 'PENDING'
                        """)
                .param(1, retailerProductId)
                .param(2, typeId)
                .update();

        // The product's family takes the type most of its products still have.
        jdbcClient.sql("""
                        UPDATE app.product_family AS family
                        SET product_type_id = (
                                SELECT assignment.product_type_id
                                FROM app.retailer_product AS member
                                JOIN app.retailer_product_type AS assignment
                                  ON assignment.retailer_product_id = member.id
                                WHERE member.product_family_id = family.id
                                GROUP BY assignment.product_type_id
                                ORDER BY COUNT(*) DESC,
                                         MAX(assignment.confidence) DESC,
                                         assignment.product_type_id
                                LIMIT 1
                            ),
                            updated_at = NOW()
                        FROM app.retailer_product AS product
                        WHERE product.id = ?
                          AND family.id = product.product_family_id
                        """)
                .param(1, retailerProductId)
                .update();

        return new ProductTypeRejectionResult(
                retailerProductId,
                code,
                "Uklonjeno. Proizvod više neće dobiti tu vrstu."
        );
    }

    private static String requireTypeCode(String typeCode) {
        if (typeCode == null || typeCode.isBlank()) {
            throw badRequest("Izaberi vrstu proizvoda.");
        }
        return typeCode.trim().toUpperCase(Locale.ROOT);
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
