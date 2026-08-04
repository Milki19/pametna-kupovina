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

    public List<NearbyStore> findNearby(
            double latitude,
            double longitude,
            int radiusMeters,
            int limit
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
                              AND store.location IS NOT NULL
                              AND store.geocoding_status IN (
                                  'AUTO_VERIFIED',
                                  'MANUALLY_VERIFIED'
                              )
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
                        FROM nearby
                        ORDER BY exact_distance_meters, store_id
                        LIMIT ?
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, radiusMeters)
                .param(4, limit)
                .query(ROW_MAPPER)
                .list();
    }
}
