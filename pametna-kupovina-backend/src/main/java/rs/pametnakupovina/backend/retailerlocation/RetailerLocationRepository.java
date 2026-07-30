package rs.pametnakupovina.backend.retailerlocation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RetailerLocationRepository {

    private static final RowMapper<RetailerLocationResponse>
            LOCATION_ROW_MAPPER = (resultSet, rowNumber) ->
            new RetailerLocationResponse(
                    resultSet.getLong("id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("retailer_name"),
                    resultSet.getString("location_name"),
                    resultSet.getString("address"),
                    resultSet.getString("city"),
                    resultSet.getDouble("latitude"),
                    resultSet.getDouble("longitude"),
                    resultSet.getDouble("distance_km")
            );

    private final JdbcClient jdbcClient;

    public RetailerLocationRepository(
            JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
    }

    public List<RetailerLocationResponse> findNearest(
            double latitude,
            double longitude,
            int limit
    ) {
        return jdbcClient.sql("""
                        WITH user_position AS (
                            SELECT ST_SetSRID(
                                ST_MakePoint(?, ?),
                                4326
                            )::geography AS location
                        )
                        SELECT rl.id,
                               r.code AS retailer_code,
                               r.name AS retailer_name,
                               rl.name AS location_name,
                               rl.address,
                               rl.city,
                               ST_Y(
                                   rl.location::geometry
                               ) AS latitude,
                               ST_X(
                                   rl.location::geometry
                               ) AS longitude,
                               ROUND(
                                   (
                                       ST_Distance(
                                           rl.location,
                                           up.location
                                       ) / 1000.0
                                   )::numeric,
                                   2
                               )::double precision AS distance_km
                        FROM app.retailer_location rl
                        JOIN app.retailer r
                          ON r.id = rl.retailer_id
                        CROSS JOIN user_position up
                        WHERE rl.active = TRUE
                        ORDER BY rl.location <-> up.location
                        LIMIT ?
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, limit)
                .query(LOCATION_ROW_MAPPER)
                .list();
    }

    public List<RetailerLocationResponse> findNearestForEachRetailer(
            double latitude,
            double longitude
    ) {
        return jdbcClient.sql("""
                    WITH user_position AS (
                        SELECT ST_SetSRID(
                            ST_MakePoint(?, ?),
                            4326
                        )::geography AS location
                    )
                    SELECT DISTINCT ON (r.id)
                           rl.id,
                           r.code AS retailer_code,
                           r.name AS retailer_name,
                           rl.name AS location_name,
                           rl.address,
                           rl.city,
                           ST_Y(
                               rl.location::geometry
                           ) AS latitude,
                           ST_X(
                               rl.location::geometry
                           ) AS longitude,
                           ROUND(
                               (
                                   ST_Distance(
                                       rl.location,
                                       up.location
                                   ) / 1000.0
                               )::numeric,
                               2
                           )::double precision AS distance_km
                    FROM app.retailer r
                    JOIN app.retailer_location rl
                      ON rl.retailer_id = r.id
                    CROSS JOIN user_position up
                    WHERE rl.active = TRUE
                    ORDER BY r.id,
                             rl.location <-> up.location
                    """)
                .param(1, longitude)
                .param(2, latitude)
                .query(LOCATION_ROW_MAPPER)
                .list();
    }
}