package rs.pametnakupovina.backend.store;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NearbyStoreRepository {

    private static final RowMapper<NearbyStore> ROW_MAPPER =
            (resultSet, rowNumber) -> new NearbyStore(
                    resultSet.getLong("store_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getString("store_format_code"),
                    resultSet.getString("store_format_name"),
                    resultSet.getString("external_code"),
                    resultSet.getString("store_name"),
                    resultSet.getString("address"),
                    resultSet.getString("city"),
                    resultSet.getDouble("latitude"),
                    resultSet.getDouble("longitude"),
                    resultSet.getDouble("distance_meters")
            );

    private final JdbcClient jdbcClient;

    public NearbyStoreRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Every shop around, in whatever market it is: a map knows no borders. */
    public List<NearbyStore> findNearby(
            double latitude,
            double longitude,
            int radiusMeters,
            int limit
    ) {
        return findNearby(
                null,
                latitude,
                longitude,
                radiusMeters,
                limit,
                false,
                null
        );
    }

    /**
     * Shops a plan can price: only the market's, because a basket is summed
     * in one currency.
     */
    public List<NearbyStore> findPricingEligibleNearby(
            int marketId,
            double latitude,
            double longitude,
            int radiusMeters,
            int limit
    ) {
        return findNearby(
                marketId,
                latitude,
                longitude,
                radiusMeters,
                limit,
                true,
                null
        );
    }

    /**
     * Najbliže radnje lanaca koji imaju bar jedan od ovih proizvoda u
     * aktuelnom cenovniku, po jedna po lancu i formatu. Proizvod je dat kao
     * porodica („Proizvod") ili kao tačan proizvod („Barkod").
     */
    public List<NearbyStore> findNearestCarrying(
            int marketId,
            double latitude,
            double longitude,
            List<Long> productFamilyIds,
            List<Long> canonicalProductIds,
            int radiusMeters,
            int limit
    ) {
        return findNearby(marketId, latitude, longitude, radiusMeters, limit, true,
                new Carrying(joined(productFamilyIds), joined(canonicalProductIds)));
    }

    private record Carrying(String familyIds, String canonicalIds) {
    }

    private static String joined(List<Long> ids) {
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    private List<NearbyStore> findNearby(
            Integer marketId,
            double latitude,
            double longitude,
            int radiusMeters,
            int limit,
            boolean pricingEligibleOnly,
            Carrying carrying
    ) {
        return jdbcClient.sql("""
                        WITH user_position AS (
                            SELECT ST_SetSRID(
                                ST_MakePoint(?, ?),
                                4326
                            )::geography AS location
                        ),
                        nearby AS (
                            SELECT store.id AS store_id,
                                   retailer.code AS retailer_code,
                                   retailer.name AS retailer_name,
                                   format.code AS store_format_code,
                                   format.name AS store_format_name,
                                   store.external_code,
                                   store.name AS store_name,
                                   store.address,
                                   store.city,
                                   ST_Y(
                                       store.location::geometry
                                   ) AS latitude,
                                   ST_X(
                                       store.location::geometry
                                   ) AS longitude,
                                   ST_Distance(
                                       store.location,
                                       user_position.location
                                   ) AS exact_distance_meters
                            FROM app.store AS store
                            JOIN app.retailer AS retailer
                              ON retailer.id = store.retailer_id
                            JOIN app.store_format AS format
                              ON format.id = store.store_format_id
                             AND format.retailer_id = store.retailer_id
                            CROSS JOIN user_position
                            WHERE store.active = TRUE
                              AND format.active = TRUE
                              AND (?::SMALLINT IS NULL OR retailer.market_id = ?::SMALLINT)
                              AND store.location IS NOT NULL
                              AND store.geocoding_status IN (
                                  'AUTO_VERIFIED',
                                  'MANUALLY_VERIFIED'
                              )
                              AND (NOT ? OR store.pricing_eligible = TRUE)
                              AND (NOT ? OR store.retailer_id IN (
                                  SELECT presence.retailer_id
                                  FROM app.product_retailer_presence AS presence
                                  WHERE presence.current_offer_count > 0
                                    AND presence.product_family_id IN (
                                        SELECT UNNEST(STRING_TO_ARRAY(?, ',')::BIGINT[])
                                        UNION
                                        SELECT member.family_id
                                        FROM app.product_family_member AS member
                                        WHERE member.canonical_product_id
                                                  = ANY(STRING_TO_ARRAY(?, ',')::BIGINT[])
                                    )
                              ))
                              AND ST_DWithin(
                                  store.location,
                                  user_position.location,
                                  ?
                              )
                        )
                        SELECT store_id,
                               retailer_code,
                               retailer_name,
                               store_format_code,
                               store_format_name,
                               external_code,
                               store_name,
                               address,
                               city,
                               latitude,
                               longitude,
                               ROUND(
                                   exact_distance_meters::numeric,
                                   1
                               )::double precision AS distance_meters
                        FROM (
                            SELECT nearby.*,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY retailer_code,
                                                    store_format_code
                                       ORDER BY exact_distance_meters
                                   ) AS format_rank
                            FROM nearby
                        ) AS ranked
                        -- Prices are per chain and format, so a second shop of
                        -- the same brand quotes the same basket and only
                        -- differs in distance. Taking the plain nearest N fills
                        -- the list with one brand in a dense city and hides a
                        -- cheaper chain a kilometre further out, so every brand
                        -- gets its closest shop first.
                        ORDER BY format_rank, exact_distance_meters, store_id
                        LIMIT ?
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, marketId, java.sql.Types.SMALLINT)
                .param(4, marketId, java.sql.Types.SMALLINT)
                .param(5, pricingEligibleOnly)
                .param(6, carrying != null)
                .param(7, carrying == null ? "" : carrying.familyIds())
                .param(8, carrying == null ? "" : carrying.canonicalIds())
                .param(9, radiusMeters)
                .param(10, limit)
                .query(ROW_MAPPER)
                .list();
    }
}
