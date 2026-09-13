package rs.pametnakupovina.backend.storepricing;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class StorePriceFormatMappingRepository {

    private static final String RETAILER_CODE = "IDEA_RODA";

    private final JdbcClient jdbcClient;

    public StorePriceFormatMappingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Transactional
    public StorePriceFormatImportResult replaceIdeaRodaMappings(
            StorePriceFormatSnapshot snapshot
    ) {
        long retailerId = findRetailerId(RETAILER_CODE);
        Map<String, VerifiedLink> existingVerifiedLinks = jdbcClient.sql("""
                        SELECT source_store_code,
                               store_external_code,
                               mapping_method
                        FROM app.store_price_format_mapping
                        WHERE retailer_id = :retailerId
                          AND verification_status = 'VERIFIED'
                          AND store_external_code IS NOT NULL
                        """)
                .param("retailerId", retailerId)
                .query((resultSet, rowNumber) -> new VerifiedLink(
                        resultSet.getString("source_store_code"),
                        resultSet.getString("store_external_code"),
                        resultSet.getString("mapping_method")
                ))
                .list()
                .stream()
                .collect(Collectors.toMap(
                        link -> link.sourceStoreCode()
                                .toUpperCase(Locale.ROOT),
                        Function.identity()
                ));

        jdbcClient.sql("""
                    UPDATE app.store_price_format_mapping
                    SET active = FALSE,
                        updated_at = NOW()
                    WHERE retailer_id = :retailerId
                    """)
                .param("retailerId", retailerId)
                .update();

        int linked = 0;
        int unlinked = 0;
        for (OfficialStorePriceFormat assignment : snapshot.assignments()) {
            MappingTarget target = resolveTarget(
                    assignment,
                    existingVerifiedLinks
            );
            if (target.storeExternalCode() == null) {
                unlinked++;
            } else {
                linked++;
            }

            jdbcClient.sql("""
                        INSERT INTO app.store_price_format_mapping (
                            retailer_id,
                            source_store_code,
                            store_external_code,
                            retailer_format_name,
                            verification_status,
                            mapping_method,
                            source_url,
                            source_last_seen_at,
                            active
                        ) VALUES (
                            :retailerId,
                            :sourceStoreCode,
                            :storeExternalCode,
                            :retailerFormatName,
                            :verificationStatus,
                            :mappingMethod,
                            :sourceUrl,
                            :sourceLastSeenAt,
                            TRUE
                        )
                        ON CONFLICT (retailer_id, source_store_code)
                        DO UPDATE SET
                            store_external_code = EXCLUDED.store_external_code,
                            retailer_format_name =
                                EXCLUDED.retailer_format_name,
                            verification_status =
                                EXCLUDED.verification_status,
                            mapping_method = EXCLUDED.mapping_method,
                            source_url = EXCLUDED.source_url,
                            source_last_seen_at =
                                EXCLUDED.source_last_seen_at,
                            active = TRUE,
                            updated_at = NOW()
                        """)
                    .param("retailerId", retailerId)
                    .param(
                            "sourceStoreCode",
                            assignment.sourceStoreCode()
                                    .toUpperCase(Locale.ROOT)
                    )
                    .param(
                            "storeExternalCode",
                            new SqlParameterValue(
                                    Types.VARCHAR,
                                    target.storeExternalCode()
                            )
                    )
                    .param(
                            "retailerFormatName",
                            IdeaRodaPriceFormatMappingClient
                                    .retailerFormatName(
                                            assignment.priceFormatCode()
                                    )
                    )
                    .param(
                            "verificationStatus",
                            target.storeExternalCode() == null
                                    ? "UNLINKED"
                                    : "VERIFIED"
                    )
                    .param("mappingMethod", target.mappingMethod())
                    .param("sourceUrl", snapshot.sourceUrl())
                    .param(
                            "sourceLastSeenAt",
                            snapshot.sourceLastModified()
                                    .atOffset(ZoneOffset.UTC)
                    )
                    .update();
        }

        int pricingEligibleStores = refreshEligibility(retailerId);
        return new StorePriceFormatImportResult(
                RETAILER_CODE,
                snapshot.assignments().size(),
                linked,
                unlinked,
                pricingEligibleStores,
                snapshot.sourceUrl(),
                "SUCCEEDED"
        );
    }

    @Transactional
    public int refreshEligibility(long retailerId) {
        Integer mappingCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.store_price_format_mapping
                        WHERE retailer_id = :retailerId
                          AND active = TRUE
                        """)
                .param("retailerId", retailerId)
                .query(Integer.class)
                .single();
        if (mappingCount == null || mappingCount == 0) {
            return 0;
        }

        retireMappingsWithoutPrices(retailerId);
        applyBrandFormatFallback(retailerId);

        jdbcClient.sql("""
                    WITH eligibility AS (
                        SELECT store.id,
                               CASE
                                   WHEN store.active = FALSE THEN FALSE
                                   WHEN store.location IS NULL THEN FALSE
                                   WHEN store.geocoding_status NOT IN (
                                       'AUTO_VERIFIED',
                                       'MANUALLY_VERIFIED'
                                   ) THEN FALSE
                                   WHEN mapping.id IS NULL THEN FALSE
                                   WHEN NOT EXISTS (
                                       SELECT 1
                                       FROM app.current_price_offer AS offer
                                       JOIN app.retailer_product AS product
                                         ON product.id =
                                            offer.retailer_product_id
                                        AND product.retailer_id =
                                            store.retailer_id
                                       WHERE offer.scope_type = 'STORE_FORMAT'
                                         AND LOWER(BTRIM(
                                             offer.retailer_format_name
                                         )) = LOWER(BTRIM(
                                             mapping.retailer_format_name
                                         ))
                                   ) THEN FALSE
                                   ELSE TRUE
                               END AS pricing_eligible,
                               CASE
                                   WHEN store.active = FALSE
                                       THEN 'STORE_INACTIVE'
                                   WHEN store.location IS NULL
                                       THEN 'LOCATION_NOT_VERIFIED'
                                   WHEN store.geocoding_status NOT IN (
                                       'AUTO_VERIFIED',
                                       'MANUALLY_VERIFIED'
                                   ) THEN 'LOCATION_NOT_VERIFIED'
                                   WHEN mapping.id IS NULL
                                       THEN 'PRICE_FORMAT_NOT_VERIFIED'
                                   WHEN NOT EXISTS (
                                       SELECT 1
                                       FROM app.current_price_offer AS offer
                                       JOIN app.retailer_product AS product
                                         ON product.id =
                                            offer.retailer_product_id
                                        AND product.retailer_id =
                                            store.retailer_id
                                       WHERE offer.scope_type = 'STORE_FORMAT'
                                         AND LOWER(BTRIM(
                                             offer.retailer_format_name
                                         )) = LOWER(BTRIM(
                                             mapping.retailer_format_name
                                         ))
                                   ) THEN 'PRICE_FORMAT_HAS_NO_PRICES'
                                   ELSE NULL
                               END AS ineligibility_reason
                        FROM app.store AS store
                        LEFT JOIN app.store_price_format_mapping AS mapping
                          ON mapping.retailer_id = store.retailer_id
                         AND mapping.store_external_code =
                             store.external_code
                         AND mapping.active = TRUE
                         AND mapping.verification_status = 'VERIFIED'
                        WHERE store.retailer_id = :retailerId
                    )
                    UPDATE app.store AS store
                    SET pricing_eligible = eligibility.pricing_eligible,
                        pricing_ineligibility_reason =
                            eligibility.ineligibility_reason,
                        updated_at = NOW()
                    FROM eligibility
                    WHERE eligibility.id = store.id
                    """)
                .param("retailerId", retailerId)
                .update();

        Integer eligibleCount = jdbcClient.sql("""
                        SELECT COUNT(*)
                        FROM app.store
                        WHERE retailer_id = :retailerId
                          AND pricing_eligible = TRUE
                        """)
                .param("retailerId", retailerId)
                .query(Integer.class)
                .single();
        return eligibleCount == null ? 0 : eligibleCount;
    }

    /**
     * A retailer can stop publishing the price formats its official store list
     * still refers to (IDEA/Roda replaced five zone price lists with three
     * brand ones on 2026-09-12). Offers from a dropped format stay behind as
     * the last known price, so presence alone does not prove the format is
     * still published: only a format carried by the retailer's newest snapshot
     * counts. Otherwise a store keeps quoting a price list that was abandoned
     * days ago and can never be corrected.
     */
    private void retireMappingsWithoutPrices(long retailerId) {
        jdbcClient.sql("""
                    UPDATE app.store_price_format_mapping AS mapping
                    SET active = FALSE,
                        updated_at = NOW()
                    WHERE mapping.retailer_id = :retailerId
                      AND mapping.active = TRUE
                      -- Before the first price import there is nothing to
                      -- judge a format against; mappings must survive that.
                      AND EXISTS (
                          SELECT 1
                          FROM app.current_price_offer AS anyOffer
                          JOIN app.retailer_product AS anyProduct
                            ON anyProduct.id = anyOffer.retailer_product_id
                           AND anyProduct.retailer_id = mapping.retailer_id
                          WHERE anyOffer.scope_type = 'STORE_FORMAT'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM app.current_price_offer AS offer
                          JOIN app.retailer_product AS product
                            ON product.id = offer.retailer_product_id
                           AND product.retailer_id = mapping.retailer_id
                          WHERE offer.scope_type = 'STORE_FORMAT'
                            AND LOWER(BTRIM(offer.retailer_format_name))
                                = LOWER(BTRIM(mapping.retailer_format_name))
                            AND offer.price_date = (
                                SELECT MAX(latest.price_date)
                                FROM app.current_price_offer AS latest
                                JOIN app.retailer_product AS latestProduct
                                  ON latestProduct.id =
                                     latest.retailer_product_id
                                 AND latestProduct.retailer_id =
                                     mapping.retailer_id
                                WHERE latest.scope_type = 'STORE_FORMAT'
                            )
                      )
                    """)
                .param("retailerId", retailerId)
                .update();
    }

    /**
     * Links a store to a published price format when the store's own brand
     * names that format exactly (RODA_LOCATION and RODA_MEGA_LOCATION both
     * carry the brand RODA, which is the published list). This is deliberately
     * an exact-name match, not a guess: if the retailer renames its lists, no
     * store is linked and the store falls out of pricing instead of quoting a
     * price nobody published for it.
     */
    private void applyBrandFormatFallback(long retailerId) {
        jdbcClient.sql("""
                    INSERT INTO app.store_price_format_mapping (
                        retailer_id,
                        source_store_code,
                        store_external_code,
                        retailer_format_name,
                        verification_status,
                        mapping_method,
                        source_url,
                        source_last_seen_at,
                        active
                    )
                    SELECT store.retailer_id,
                           'BRAND:' || store.external_code,
                           store.external_code,
                           published.retailer_format_name,
                           'VERIFIED',
                           'BRAND_FORMAT_FALLBACK',
                           'derived:store_format.code',
                           NOW(),
                           TRUE
                    FROM app.store AS store
                    JOIN app.store_format AS format
                      ON format.id = store.store_format_id
                    JOIN (
                        SELECT DISTINCT product.retailer_id,
                               offer.retailer_format_name
                        FROM app.current_price_offer AS offer
                        JOIN app.retailer_product AS product
                          ON product.id = offer.retailer_product_id
                        WHERE offer.scope_type = 'STORE_FORMAT'
                    ) AS published
                      ON published.retailer_id = store.retailer_id
                     AND UPPER(BTRIM(published.retailer_format_name))
                         = UPPER(SPLIT_PART(format.code, '_', 1))
                    WHERE store.retailer_id = :retailerId
                      AND store.active = TRUE
                      AND store.external_code IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1
                          FROM app.store_price_format_mapping AS existing
                          WHERE existing.retailer_id = store.retailer_id
                            AND existing.store_external_code =
                                store.external_code
                            AND existing.active = TRUE
                            AND existing.verification_status = 'VERIFIED'
                      )
                    ON CONFLICT (retailer_id, source_store_code)
                    DO UPDATE SET
                        store_external_code = EXCLUDED.store_external_code,
                        retailer_format_name = EXCLUDED.retailer_format_name,
                        verification_status = 'VERIFIED',
                        mapping_method = EXCLUDED.mapping_method,
                        source_last_seen_at = EXCLUDED.source_last_seen_at,
                        active = TRUE,
                        updated_at = NOW()
                    """)
                .param("retailerId", retailerId)
                .update();
    }

    private MappingTarget resolveTarget(
            OfficialStorePriceFormat assignment,
            Map<String, VerifiedLink> existingVerifiedLinks
    ) {
        if (assignment.priceFormatCode().startsWith("R")) {
            return new MappingTarget(
                    "RODA_" + assignment.sourceStoreCode().substring(2),
                    "OFFICIAL_STORE_CODE"
            );
        }

        VerifiedLink existing = existingVerifiedLinks.get(
                assignment.sourceStoreCode().toUpperCase(Locale.ROOT)
        );
        if (existing != null) {
            return new MappingTarget(
                    existing.storeExternalCode(),
                    existing.mappingMethod()
            );
        }
        return new MappingTarget(null, "OFFICIAL_LIST_UNLINKED");
    }

    private long findRetailerId(String retailerCode) {
        return jdbcClient.sql("""
                        SELECT id
                        FROM app.retailer
                        WHERE code = :retailerCode
                        """)
                .param("retailerCode", retailerCode)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Prodavac nije pronađen: " + retailerCode
                ));
    }

    private record VerifiedLink(
            String sourceStoreCode,
            String storeExternalCode,
            String mappingMethod
    ) {
    }

    private record MappingTarget(
            String storeExternalCode,
            String mappingMethod
    ) {
    }
}
