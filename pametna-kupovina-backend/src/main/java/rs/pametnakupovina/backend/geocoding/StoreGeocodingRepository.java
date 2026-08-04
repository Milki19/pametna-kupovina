package rs.pametnakupovina.backend.geocoding;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class StoreGeocodingRepository {

    private static final RowMapper<StoreGeocodingState> ROW_MAPPER =
            (resultSet, rowNumber) -> new StoreGeocodingState(
                    resultSet.getLong("store_id"),
                    resultSet.getString("retailer_code"),
                    resultSet.getString("external_code"),
                    resultSet.getString("address"),
                    resultSet.getString("city"),
                    resultSet.getObject(
                            "candidate_latitude",
                            Double.class
                    ),
                    resultSet.getObject(
                            "candidate_longitude",
                            Double.class
                    ),
                    resultSet.getObject(
                            "applied_latitude",
                            Double.class
                    ),
                    resultSet.getObject(
                            "applied_longitude",
                            Double.class
                    ),
                    resultSet.getString("geocoding_query"),
                    resultSet.getString("geocoding_source"),
                    resultSet.getString("geocoding_source_reference"),
                    resultSet.getString("geocoding_matched_address"),
                    resultSet.getBigDecimal("geocoding_confidence"),
                    StoreGeocodingStatus.valueOf(
                            resultSet.getString("geocoding_status")
                    ),
                    resultSet.getString(
                            "geocoding_suspicious_reason"
                    ),
                    resultSet.getString("geocoding_review_note"),
                    resultSet.getObject(
                            "geocoded_at",
                            java.time.OffsetDateTime.class
                    ),
                    resultSet.getObject(
                            "geocoding_reviewed_at",
                            java.time.OffsetDateTime.class
                    )
            );

    private static final String STATE_SELECT = """
            SELECT store.id AS store_id,
                   retailer.code AS retailer_code,
                   store.external_code,
                   store.address,
                   store.city,
                   ST_Y(
                       store.geocoding_candidate::geometry
                   ) AS candidate_latitude,
                   ST_X(
                       store.geocoding_candidate::geometry
                   ) AS candidate_longitude,
                   ST_Y(store.location::geometry) AS applied_latitude,
                   ST_X(store.location::geometry) AS applied_longitude,
                   store.geocoding_query,
                   store.geocoding_source,
                   store.geocoding_source_reference,
                   store.geocoding_matched_address,
                   store.geocoding_confidence,
                   store.geocoding_status,
                   store.geocoding_suspicious_reason,
                   store.geocoding_review_note,
                   store.geocoded_at,
                   store.geocoding_reviewed_at
            FROM app.store AS store
            JOIN app.retailer AS retailer
              ON retailer.id = store.retailer_id
            """;

    private final JdbcClient jdbcClient;

    public StoreGeocodingRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<StoreGeocodingState> findById(Long storeId) {
        return jdbcClient.sql(
                        STATE_SELECT + " WHERE store.id = ?"
                )
                .param(storeId)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<StoreGeocodingState> findReviewQueue(String city) {
        return jdbcClient.sql(
                        STATE_SELECT + """
                        WHERE store.geocoding_status = 'NEEDS_REVIEW'
                          AND LOWER(BTRIM(store.city)) =
                              LOWER(BTRIM(?))
                        ORDER BY retailer.code, store.external_code
                        """
                )
                .param(city)
                .query(ROW_MAPPER)
                .list();
    }

    public StoreGeocodingState saveCandidate(
            Long storeId,
            double latitude,
            double longitude,
            BigDecimal confidence,
            String query,
            String source,
            String sourceReference,
            String matchedAddress,
            StoreGeocodingStatus status,
            String suspiciousReason
    ) {
        boolean applyCoordinates =
                status == StoreGeocodingStatus.AUTO_VERIFIED;

        jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT ST_SetSRID(
                                ST_MakePoint(?, ?),
                                4326
                            )::geography AS location
                        )
                        UPDATE app.store AS store
                        SET geocoding_candidate = candidate.location,
                            location = CASE
                                WHEN ? THEN candidate.location
                                ELSE NULL
                            END,
                            geocoding_query = ?,
                            geocoding_source = ?,
                            geocoding_source_reference = ?,
                            geocoding_matched_address = ?,
                            geocoding_confidence = ?,
                            geocoding_status = ?,
                            geocoding_suspicious_reason = ?,
                            geocoded_at = NOW(),
                            geocoding_review_note = NULL,
                            geocoding_reviewed_at = NULL,
                            updated_at = NOW()
                        FROM candidate
                        WHERE store.id = ?
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, applyCoordinates)
                .param(4, query)
                .param(5, source)
                .param(6, sourceReference, Types.VARCHAR)
                .param(7, matchedAddress)
                .param(8, confidence)
                .param(9, status.name())
                .param(10, suspiciousReason, Types.VARCHAR)
                .param(11, storeId)
                .update();

        return requiredState(storeId);
    }

    public StoreGeocodingState review(
            Long storeId,
            StoreGeocodingStatus status,
            double latitude,
            double longitude,
            String reviewNote
    ) {
        boolean applyCoordinates =
                status == StoreGeocodingStatus.MANUALLY_VERIFIED;

        jdbcClient.sql("""
                        WITH reviewed_location AS (
                            SELECT ST_SetSRID(
                                ST_MakePoint(?, ?),
                                4326
                            )::geography AS location
                        )
                        UPDATE app.store AS store
                        SET location = CASE
                                WHEN ? THEN reviewed_location.location
                                ELSE NULL
                            END,
                            geocoding_status = ?,
                            geocoding_review_note = ?,
                            geocoding_reviewed_at = NOW(),
                            updated_at = NOW()
                        FROM reviewed_location
                        WHERE store.id = ?
                        """)
                .param(1, longitude)
                .param(2, latitude)
                .param(3, applyCoordinates)
                .param(4, status.name())
                .param(5, reviewNote)
                .param(6, storeId)
                .update();

        return requiredState(storeId);
    }

    private StoreGeocodingState requiredState(Long storeId) {
        return findById(storeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Objekat nije pronađen: " + storeId
                ));
    }
}
