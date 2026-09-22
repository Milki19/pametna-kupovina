package rs.pametnakupovina.backend.dataquality;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DataQualityService {

    private final JdbcClient jdbcClient;
    private final int stalePriceDays;

    public DataQualityService(
            JdbcClient jdbcClient,
            @Value("${data-quality.stale-price-days:2}")
            int stalePriceDays
    ) {
        this.jdbcClient = jdbcClient;
        this.stalePriceDays = stalePriceDays;
    }

    public DataQualityReport report() {
        Instant generatedAt = jdbcClient.sql("SELECT NOW()")
                .query(Instant.class)
                .single();
        DataQualityReport.Summary summary = loadSummary();
        DataQualityReport.LocationSummary locations = loadLocations();
        List<DataQualityReport.RetailerQuality> retailers = loadRetailers();
        List<DataQualityReport.SourceQuality> sources = loadSources(
                generatedAt
        );

        boolean critical = locations.unsafePricingEligible() > 0
                || sources.stream().anyMatch(source ->
                "CRITICAL".equals(source.health()));
        boolean warning = summary.unmatchedRetailerProducts() > 0
                || summary.uncategorizedRetailerProducts() > 0
                || summary.untypedRetailerProducts() > 0
                || summary.pendingProductTypeCandidates() > 0
                || summary.pendingIdentityCandidates() > 0
                || summary.suspectedDuplicateProductFamilies() > 0
                || summary.conflictingCanonicalBarcodes() > 0
                || summary.invalidCurrentPriceOffers() > 0
                || summary.staleCurrentPriceOffers() > 0
                || sources.stream().anyMatch(source ->
                "WARNING".equals(source.health()));

        return new DataQualityReport(
                generatedAt,
                critical ? "CRITICAL" : warning ? "WARNING" : "HEALTHY",
                summary,
                locations,
                retailers,
                sources
        );
    }

    public List<StorePricingReview> reviewLocations(
            boolean onlyIneligible,
            int limit
    ) {
        return jdbcClient.sql("""
                        SELECT store.id,
                               retailer.code AS retailer_code,
                               store.external_code,
                               store.name AS store_name,
                               store.address,
                               store.city,
                               format.code AS store_format_code,
                               format.name AS store_format_name,
                               store.active,
                               store.geocoding_status,
                               store.location IS NOT NULL AS has_coordinates,
                               store.pricing_eligible,
                               store.pricing_ineligibility_reason,
                               source.code AS source_code
                        FROM app.store AS store
                        JOIN app.retailer AS retailer
                          ON retailer.id = store.retailer_id
                        JOIN app.store_format AS format
                          ON format.id = store.store_format_id
                        LEFT JOIN app.retailer_data_source AS source
                          ON source.id = store.data_source_id
                        WHERE (NOT :onlyIneligible OR NOT store.pricing_eligible)
                        ORDER BY store.pricing_eligible,
                                 retailer.code,
                                 store.city,
                                 store.name,
                                 store.id
                        LIMIT :limit
                        """)
                .param("onlyIneligible", onlyIneligible)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> new StorePricingReview(
                        resultSet.getLong("id"),
                        resultSet.getString("retailer_code"),
                        resultSet.getString("external_code"),
                        resultSet.getString("store_name"),
                        resultSet.getString("address"),
                        resultSet.getString("city"),
                        resultSet.getString("store_format_code"),
                        resultSet.getString("store_format_name"),
                        resultSet.getBoolean("active"),
                        resultSet.getString("geocoding_status"),
                        resultSet.getBoolean("has_coordinates"),
                        resultSet.getBoolean("pricing_eligible"),
                        resultSet.getString("pricing_ineligibility_reason"),
                        resultSet.getString("source_code")
                ))
                .list();
    }

    public List<ProductTypeCandidateReview> reviewProductTypes(int limit) {
        return reviewProductTypes(limit, null);
    }

    /** Suggestions waiting for review, of one type when a code is given. */
    public List<ProductTypeCandidateReview> reviewProductTypes(int limit, String typeCode) {
        return jdbcClient.sql("""
                        SELECT product.id AS retailer_product_id,
                               retailer.code AS retailer_code,
                               product.source_product_key,
                               product.name AS product_name,
                               product.category_code,
                               product.category_name,
                               type.code AS product_type_code,
                               type.name AS product_type_name,
                               candidate.confidence,
                               candidate.prediction_source,
                               candidate.evidence,
                               candidate.algorithm_version
                        FROM app.product_type_candidate AS candidate
                        JOIN app.retailer_product AS product
                          ON product.id = candidate.retailer_product_id
                        JOIN app.retailer AS retailer
                          ON retailer.id = product.retailer_id
                        JOIN app.product_type AS type
                          ON type.id = candidate.product_type_id
                        WHERE candidate.status = 'PENDING'
                          AND (CAST(:typeCode AS TEXT) IS NULL OR type.code = CAST(:typeCode AS TEXT))
                        ORDER BY candidate.confidence DESC,
                                 retailer.code,
                                 product.name,
                                 product.id
                        LIMIT :limit
                        """)
                .param("limit", limit)
                .param("typeCode", typeCode == null || typeCode.isBlank()
                        ? null
                        : typeCode.trim().toUpperCase(Locale.ROOT))
                .query((resultSet, rowNumber) ->
                        new ProductTypeCandidateReview(
                                resultSet.getLong("retailer_product_id"),
                                resultSet.getString("retailer_code"),
                                resultSet.getString("source_product_key"),
                                resultSet.getString("product_name"),
                                resultSet.getString("category_code"),
                                resultSet.getString("category_name"),
                                resultSet.getString("product_type_code"),
                                resultSet.getString("product_type_name"),
                                resultSet.getBigDecimal("confidence"),
                                resultSet.getString("prediction_source"),
                                resultSet.getString("evidence"),
                                resultSet.getString("algorithm_version")
                        ))
                .list();
    }

    @Transactional
    public ProductTypeReviewResult reviewProductType(
            long retailerProductId,
            ProductTypeReviewRequest request
    ) {
        if (retailerProductId <= 0) {
            throw badRequest("retailerProductId mora biti pozitivan");
        }

        String action = normalizedRequired(
                request == null ? null : request.action(),
                "action"
        ).toUpperCase(Locale.ROOT);
        String productTypeCode = normalizedRequired(
                request == null ? null : request.productTypeCode(),
                "productTypeCode"
        ).toUpperCase(Locale.ROOT);

        if (!"ACCEPT".equals(action) && !"REJECT".equals(action)) {
            throw badRequest("action mora biti ACCEPT ili REJECT");
        }

        ReviewedCandidate candidate = jdbcClient.sql("""
                        SELECT candidate.id,
                               candidate.product_type_id,
                               type.code AS product_type_code,
                               candidate.algorithm_version
                        FROM app.product_type_candidate AS candidate
                        JOIN app.product_type AS type
                          ON type.id = candidate.product_type_id
                        WHERE candidate.retailer_product_id = :productId
                          AND type.code = :typeCode
                          AND candidate.status = 'PENDING'
                        ORDER BY candidate.confidence DESC,
                                 candidate.id
                        LIMIT 1
                        """)
                .param("productId", retailerProductId)
                .param("typeCode", productTypeCode)
                .query((resultSet, rowNumber) -> new ReviewedCandidate(
                        resultSet.getLong("id"),
                        resultSet.getLong("product_type_id"),
                        resultSet.getString("product_type_code"),
                        resultSet.getString("algorithm_version")
                ))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Predlog tipa proizvoda nije pronađen"
                ));

        if ("ACCEPT".equals(action)) {
            jdbcClient.sql("""
                        INSERT INTO app.retailer_product_type (
                            retailer_product_id,
                            product_type_id,
                            confidence,
                            assignment_source,
                            evidence,
                            reviewed,
                            algorithm_version
                        )
                        VALUES (
                            :productId,
                            :typeId,
                            1.0000,
                            'MANUAL_REVIEW',
                            :evidence,
                            TRUE,
                            :algorithmVersion
                        )
                        ON CONFLICT (retailer_product_id) DO UPDATE SET
                            product_type_id = EXCLUDED.product_type_id,
                            confidence = EXCLUDED.confidence,
                            assignment_source = EXCLUDED.assignment_source,
                            evidence = EXCLUDED.evidence,
                            reviewed = TRUE,
                            algorithm_version = EXCLUDED.algorithm_version,
                            updated_at = NOW()
                        """)
                    .param("productId", retailerProductId)
                    .param("typeId", candidate.productTypeId())
                    .param(
                            "evidence",
                            "CANDIDATE:" + candidate.id()
                    )
                    .param("algorithmVersion", candidate.algorithmVersion())
                    .update();

            // Accepted after all: an earlier rejection no longer holds.
            jdbcClient.sql("""
                        DELETE FROM app.retailer_product_type_rejection
                        WHERE retailer_product_id = :productId
                          AND product_type_id = :typeId
                        """)
                    .param("productId", retailerProductId)
                    .param("typeId", candidate.productTypeId())
                    .update();

            jdbcClient.sql("""
                        UPDATE app.product_type_candidate
                        SET status = CASE
                                WHEN id = :candidateId
                                    THEN 'ACCEPTED'
                                ELSE 'REJECTED'
                            END,
                            reviewed_at = NOW(),
                            updated_at = NOW()
                        WHERE retailer_product_id = :productId
                          AND status = 'PENDING'
                        """)
                    .param("candidateId", candidate.id())
                    .param("productId", retailerProductId)
                    .update();
        } else {
            jdbcClient.sql("""
                        UPDATE app.product_type_candidate
                        SET status = 'REJECTED',
                            reviewed_at = NOW(),
                            updated_at = NOW()
                        WHERE id = :candidateId
                        """)
                    .param("candidateId", candidate.id())
                    .update();

            // Not suggested again by a later version of the taxonomy (V83).
            jdbcClient.sql("""
                        INSERT INTO app.retailer_product_type_rejection (
                            retailer_product_id,
                            product_type_id
                        )
                        VALUES (:productId, :typeId)
                        ON CONFLICT DO NOTHING
                        """)
                    .param("productId", retailerProductId)
                    .param("typeId", candidate.productTypeId())
                    .update();
        }

        return new ProductTypeReviewResult(
                retailerProductId,
                action,
                candidate.productTypeCode(),
                true
        );
    }

    private DataQualityReport.Summary loadSummary() {
        return jdbcClient.sql("""
                        SELECT
                            (SELECT COUNT(*) FROM app.canonical_product)
                                AS canonical_products,
                            (SELECT COUNT(*) FROM app.product_family)
                                AS product_families,
                            (SELECT COUNT(*) FROM app.retailer_product)
                                AS retailer_products,
                            (SELECT COUNT(*) FROM app.current_price_offer)
                                AS current_price_offers,
                            (SELECT COUNT(*) FROM app.retailer_product
                             WHERE canonical_product_id IS NULL)
                                AS unmatched_retailer_products,
                            (SELECT COUNT(*)
                             FROM app.retailer_product AS product
                             WHERE NOT EXISTS (
                                 SELECT 1
                                 FROM app.retailer_product_category AS category
                                 WHERE category.retailer_product_id = product.id
                             )) AS uncategorized_retailer_products,
                            (SELECT COUNT(*)
                             FROM app.retailer_product_type)
                                AS typed_retailer_products,
                            (SELECT COUNT(*)
                             FROM app.retailer_product AS product
                             WHERE NOT EXISTS (
                                 SELECT 1
                                 FROM app.retailer_product_type AS type
                                 WHERE type.retailer_product_id = product.id
                             )) AS untyped_retailer_products,
                            (SELECT COUNT(*)
                             FROM app.product_type_candidate
                             WHERE status = 'PENDING')
                                AS pending_product_type_candidates,
                            (SELECT COUNT(*)
                             FROM app.retailer_product_attribute)
                                AS extracted_product_attributes,
                            (SELECT COUNT(*)
                             FROM app.product_identity_candidate
                             WHERE status = 'PENDING')
                                AS pending_identity_candidates,
                            (SELECT COUNT(*)
                             FROM app.product_family
                             WHERE review_status = 'REVIEW_REQUIRED')
                                AS suspected_duplicate_product_families,
                            (SELECT COUNT(*)
                             FROM (
                                 SELECT barcode
                                 FROM app.canonical_product
                                 WHERE NULLIF(BTRIM(barcode), '') IS NOT NULL
                                 GROUP BY barcode
                                 HAVING COUNT(*) > 1
                             ) AS conflict)
                                AS conflicting_canonical_barcodes,
                            (SELECT COUNT(*)
                             FROM app.current_price_offer
                             WHERE NOT (
                                 COALESCE(regular_price, 0) > 0
                                 OR COALESCE(discounted_price, 0) > 0
                             )) AS invalid_current_price_offers,
                            (SELECT COUNT(*)
                             FROM app.current_price_offer
                             WHERE last_seen_date < CURRENT_DATE - :staleDays)
                                AS stale_current_price_offers
                        """)
                .param("staleDays", stalePriceDays)
                .query((resultSet, rowNumber) ->
                        new DataQualityReport.Summary(
                                resultSet.getLong("canonical_products"),
                                resultSet.getLong("product_families"),
                                resultSet.getLong("retailer_products"),
                                resultSet.getLong("current_price_offers"),
                                resultSet.getLong("unmatched_retailer_products"),
                                resultSet.getLong("uncategorized_retailer_products"),
                                resultSet.getLong("typed_retailer_products"),
                                resultSet.getLong("untyped_retailer_products"),
                                resultSet.getLong(
                                        "pending_product_type_candidates"
                                ),
                                resultSet.getLong(
                                        "extracted_product_attributes"
                                ),
                                resultSet.getLong("pending_identity_candidates"),
                                resultSet.getLong("suspected_duplicate_product_families"),
                                resultSet.getLong("conflicting_canonical_barcodes"),
                                resultSet.getLong("invalid_current_price_offers"),
                                resultSet.getLong("stale_current_price_offers")
                        ))
                .single();
    }

    private DataQualityReport.LocationSummary loadLocations() {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FILTER (WHERE active) AS total_active,
                               COUNT(*) FILTER (
                                   WHERE active
                                     AND location IS NOT NULL
                                     AND geocoding_status IN (
                                         'AUTO_VERIFIED',
                                         'MANUALLY_VERIFIED'
                                     )
                               ) AS geocoded_verified,
                               COUNT(*) FILTER (
                                   WHERE active AND pricing_eligible
                               ) AS pricing_eligible,
                               COUNT(*) FILTER (
                                   WHERE active AND NOT pricing_eligible
                               ) AS pricing_ineligible,
                               COUNT(*) FILTER (
                                   WHERE pricing_eligible
                                     AND (
                                         NOT active
                                         OR location IS NULL
                                         OR geocoding_status NOT IN (
                                             'AUTO_VERIFIED',
                                             'MANUALLY_VERIFIED'
                                         )
                                     )
                               ) AS unsafe_pricing_eligible
                        FROM app.store
                        """)
                .query((resultSet, rowNumber) ->
                        new DataQualityReport.LocationSummary(
                                resultSet.getLong("total_active"),
                                resultSet.getLong("geocoded_verified"),
                                resultSet.getLong("pricing_eligible"),
                                resultSet.getLong("pricing_ineligible"),
                                resultSet.getLong("unsafe_pricing_eligible")
                        ))
                .single();
    }

    private List<DataQualityReport.RetailerQuality> loadRetailers() {
        return jdbcClient.sql("""
                        WITH product_stats AS (
                            SELECT product.retailer_id,
                                   COUNT(*) AS products,
                                   COUNT(*) FILTER (
                                       WHERE product.canonical_product_id
                                           IS NOT NULL
                                   ) AS matched_products,
                                   COUNT(category.retailer_product_id)
                                       AS categorized_products
                            FROM app.retailer_product AS product
                            LEFT JOIN app.retailer_product_category AS category
                              ON category.retailer_product_id = product.id
                            GROUP BY product.retailer_id
                        ),
                        offer_stats AS (
                            SELECT product.retailer_id,
                                   COUNT(*) AS current_offers,
                                   MAX(offer.price_date) AS latest_price_date
                            FROM app.current_price_offer AS offer
                            JOIN app.retailer_product AS product
                              ON product.id = offer.retailer_product_id
                            GROUP BY product.retailer_id
                        ),
                        store_stats AS (
                            SELECT store.retailer_id,
                                   COUNT(*) FILTER (WHERE store.active)
                                       AS active_stores,
                                   COUNT(*) FILTER (
                                       WHERE store.active
                                         AND store.pricing_eligible
                                   ) AS pricing_eligible_stores
                            FROM app.store AS store
                            GROUP BY store.retailer_id
                        )
                        SELECT retailer.code,
                               retailer.name,
                               COALESCE(product_stats.products, 0) AS products,
                               COALESCE(product_stats.matched_products, 0)
                                   AS matched_products,
                               COALESCE(product_stats.categorized_products, 0)
                                   AS categorized_products,
                               COALESCE(offer_stats.current_offers, 0)
                                   AS current_offers,
                               offer_stats.latest_price_date,
                               COALESCE(store_stats.active_stores, 0)
                                   AS active_stores,
                               COALESCE(store_stats.pricing_eligible_stores, 0)
                                   AS pricing_eligible_stores
                        FROM app.retailer AS retailer
                        LEFT JOIN product_stats
                          ON product_stats.retailer_id = retailer.id
                        LEFT JOIN offer_stats
                          ON offer_stats.retailer_id = retailer.id
                        LEFT JOIN store_stats
                          ON store_stats.retailer_id = retailer.id
                        ORDER BY retailer.code
                        """)
                .query((resultSet, rowNumber) ->
                        new DataQualityReport.RetailerQuality(
                                resultSet.getString("code"),
                                resultSet.getString("name"),
                                resultSet.getLong("products"),
                                resultSet.getLong("matched_products"),
                                resultSet.getLong("categorized_products"),
                                resultSet.getLong("current_offers"),
                                resultSet.getObject(
                                        "latest_price_date",
                                        LocalDate.class
                                ),
                                resultSet.getLong("active_stores"),
                                resultSet.getLong("pricing_eligible_stores")
                        ))
                .list();
    }

    private List<DataQualityReport.SourceQuality> loadSources(
            Instant generatedAt
    ) {
        return jdbcClient.sql("""
                        SELECT source.id,
                               retailer.code AS retailer_code,
                               source.code AS source_code,
                               source.source_type,
                               source.active,
                               source.last_status,
                               source.last_started_at,
                               source.last_success_at,
                               source.last_snapshot_date,
                               source.last_rows_read,
                               source.last_rows_saved,
                               source.expected_min_rows_saved,
                               source.max_success_age_hours,
                               source.minimum_volume_ratio,
                               source.consecutive_success_count,
                               source.consecutive_failure_count,
                               latest.stage AS latest_stage,
                               latest.downloaded_bytes,
                               latest.last_progress_at,
                               EXISTS (
                                   SELECT 1
                                   FROM app.store AS store
                                   WHERE store.retailer_id = source.retailer_id
                                     AND store.active = TRUE
                               ) AS retailer_has_stores,
                               CASE
                                   WHEN previous.rows_saved > 0
                                    AND latest_success.rows_saved IS NOT NULL
                                   THEN ROUND(
                                       latest_success.rows_saved::NUMERIC
                                       / previous.rows_saved,
                                       4
                                   )
                                   ELSE NULL
                               END AS volume_ratio
                        FROM app.retailer_data_source AS source
                        JOIN app.retailer AS retailer
                          ON retailer.id = source.retailer_id
                        LEFT JOIN LATERAL (
                            SELECT run.stage,
                                   run.downloaded_bytes,
                                   run.last_progress_at
                            FROM app.import_run AS run
                            WHERE run.data_source_id = source.id
                            ORDER BY run.started_at DESC, run.id DESC
                            LIMIT 1
                        ) AS latest ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT run.id,
                                   run.started_at,
                                   run.rows_saved
                            FROM app.import_run AS run
                            WHERE run.data_source_id = source.id
                              AND run.status IN (
                                  'SUCCEEDED',
                                  'SUCCEEDED_WITH_ERRORS'
                              )
                            ORDER BY run.started_at DESC, run.id DESC
                            LIMIT 1
                        ) AS latest_success ON TRUE
                        LEFT JOIN LATERAL (
                            SELECT run.rows_saved
                            FROM app.import_run AS run
                            WHERE run.data_source_id = source.id
                              AND run.status IN (
                                  'SUCCEEDED',
                                  'SUCCEEDED_WITH_ERRORS'
                              )
                              AND (
                                  run.started_at,
                                  run.id
                              ) < (
                                  latest_success.started_at,
                                  latest_success.id
                              )
                            ORDER BY run.started_at DESC, run.id DESC
                            LIMIT 1
                        ) AS previous ON TRUE
                        ORDER BY retailer.code, source.code
                        """)
                .query((resultSet, rowNumber) -> {
                    boolean active = resultSet.getBoolean("active");
                    String lastStatus = resultSet.getString("last_status");
                    Instant lastStartedAt = instant(
                            resultSet.getTimestamp("last_started_at")
                    );
                    Instant lastSuccessAt = instant(
                            resultSet.getTimestamp("last_success_at")
                    );
                    Integer lastRowsSaved = resultSet.getObject(
                            "last_rows_saved",
                            Integer.class
                    );
                    Integer expectedRows = resultSet.getObject(
                            "expected_min_rows_saved",
                            Integer.class
                    );
                    Integer maxAgeHours = resultSet.getObject(
                            "max_success_age_hours",
                            Integer.class
                    );
                    BigDecimal ratio = resultSet.getBigDecimal("volume_ratio");
                    BigDecimal minimumRatio = resultSet.getBigDecimal(
                            "minimum_volume_ratio"
                    );
                    Instant lastProgressAt = instant(
                            resultSet.getTimestamp("last_progress_at")
                    );
                    List<String> alerts = sourceAlerts(
                            active,
                            resultSet.getString("source_type"),
                            resultSet.getBoolean("retailer_has_stores"),
                            lastStatus,
                            lastSuccessAt,
                            lastRowsSaved,
                            expectedRows,
                            maxAgeHours,
                            ratio,
                            minimumRatio,
                            lastProgressAt,
                            generatedAt
                    );
                    String health = health(active, alerts);

                    return new DataQualityReport.SourceQuality(
                            resultSet.getLong("id"),
                            resultSet.getString("retailer_code"),
                            resultSet.getString("source_code"),
                            resultSet.getString("source_type"),
                            active,
                            health,
                            lastStatus,
                            lastStartedAt,
                            lastSuccessAt,
                            resultSet.getObject(
                                    "last_snapshot_date",
                                    LocalDate.class
                            ),
                            resultSet.getObject(
                                    "last_rows_read",
                                    Integer.class
                            ),
                            lastRowsSaved,
                            expectedRows,
                            maxAgeHours,
                            ratio,
                            minimumRatio,
                            resultSet.getInt("consecutive_success_count"),
                            resultSet.getInt("consecutive_failure_count"),
                            resultSet.getString("latest_stage"),
                            resultSet.getObject(
                                    "downloaded_bytes",
                                    Long.class
                            ),
                            lastProgressAt,
                            alerts
                    );
                })
                .list();
    }

    static List<String> sourceAlerts(
            boolean active,
            String sourceType,
            boolean retailerHasStores,
            String lastStatus,
            Instant lastSuccessAt,
            Integer lastRowsSaved,
            Integer expectedRows,
            Integer maxAgeHours,
            BigDecimal volumeRatio,
            BigDecimal minimumVolumeRatio,
            Instant lastProgressAt,
            Instant generatedAt
    ) {
        if (!active) {
            return List.of();
        }

        // A store locator that was never needed: the chain's stores came in
        // another way (Lidl, DIS). It kept the whole report CRITICAL.
        if ("STORE_LOCATIONS".equals(sourceType)
                && "NEVER_RUN".equals(lastStatus)
                && retailerHasStores) {
            return List.of();
        }

        List<String> alerts = new ArrayList<>();

        if ("FAILED".equals(lastStatus)) {
            alerts.add("LAST_RUN_FAILED");
        } else if ("NEVER_RUN".equals(lastStatus)) {
            alerts.add("NEVER_SUCCEEDED");
        } else if ("SUCCEEDED_WITH_ERRORS".equals(lastStatus)) {
            alerts.add("ROWS_SKIPPED_WITH_ERRORS");
        }

        if ("RUNNING".equals(lastStatus)
                && lastProgressAt != null
                && lastProgressAt.isBefore(generatedAt.minusSeconds(1800))) {
            alerts.add("IMPORT_PROGRESS_STALE");
        }

        if (maxAgeHours != null && (
                lastSuccessAt == null
                        || lastSuccessAt.isBefore(
                        generatedAt.minusSeconds(maxAgeHours * 3600L)
                )
        )) {
            alerts.add("SOURCE_DATA_STALE");
        }

        if (expectedRows != null
                && (lastRowsSaved == null || lastRowsSaved < expectedRows)) {
            alerts.add("ROW_COUNT_BELOW_MINIMUM");
        }

        if (volumeRatio != null
                && volumeRatio.compareTo(minimumVolumeRatio) < 0) {
            alerts.add("ROW_VOLUME_DROPPED");
        }

        return List.copyOf(alerts);
    }

    private String health(boolean active, List<String> alerts) {
        if (!active) {
            return "DISABLED";
        }
        if (alerts.stream().anyMatch(alert ->
                alert.equals("LAST_RUN_FAILED")
                        || alert.equals("IMPORT_PROGRESS_STALE")
                        || alert.equals("SOURCE_DATA_STALE")
                        || alert.equals("ROW_COUNT_BELOW_MINIMUM")
        )) {
            return "CRITICAL";
        }
        if (!alerts.isEmpty()) {
            return "WARNING";
        }

        return "HEALTHY";
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalizedRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw badRequest(fieldName + " je obavezan");
        }

        return value.trim();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private record ReviewedCandidate(
            long id,
            long productTypeId,
            String productTypeCode,
            String algorithmVersion
    ) {
    }
}
