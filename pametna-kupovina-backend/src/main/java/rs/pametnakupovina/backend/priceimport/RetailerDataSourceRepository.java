package rs.pametnakupovina.backend.priceimport;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class RetailerDataSourceRepository {

    private final JdbcClient jdbcClient;

    public RetailerDataSourceRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public PriceSource resolveCatalog(
            Long retailerId,
            String fallbackUrl,
            boolean storeScoped
    ) {
        Optional<PriceSource> registered = jdbcClient.sql("""
                        SELECT source.id,
                               source.source_url,
                               source.discovery_url,
                               source.parser_profile
                        FROM app.retailer_data_source AS source
                        WHERE source.retailer_id = ?
                          AND source.source_type = 'PRICE_CATALOG'
                          AND source.active = TRUE
                          AND (
                              (? AND source.price_scope = 'STORE')
                              OR
                              (NOT ? AND source.price_scope <> 'STORE')
                          )
                        ORDER BY source.id ASC
                        LIMIT 1
                        """)
                .param(1, retailerId)
                .param(2, storeScoped)
                .param(3, storeScoped)
                .query((resultSet, rowNumber) -> new PriceSource(
                        resultSet.getLong("id"),
                        firstNonBlank(
                                resultSet.getString("source_url"),
                                fallbackUrl
                        ),
                        resultSet.getString("discovery_url"),
                        resultSet.getString("parser_profile")
                ))
                .optional();

        return registered.orElseGet(() -> new PriceSource(
                null,
                fallbackUrl,
                null,
                null
        ));
    }

    public void updateResolvedSourceUrl(
            Long dataSourceId,
            String sourceUrl
    ) {
        if (dataSourceId == null) {
            return;
        }

        jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET source_url = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """)
                .param(1, sourceUrl)
                .param(2, dataSourceId)
                .update();
    }

    public boolean tryMarkRunning(Long dataSourceId) {
        if (dataSourceId == null) {
            return true;
        }

        return jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET last_status = 'RUNNING',
                        last_started_at = NOW(),
                        last_error = NULL,
                        updated_at = NOW()
                    WHERE id = ?
                      AND (
                          last_status <> 'RUNNING'
                          OR last_started_at IS NULL
                          OR last_started_at < NOW() - INTERVAL '6 hours'
                      )
                    """)
                .param(1, dataSourceId)
                .update() == 1;
    }

    public void markSucceeded(
            Long dataSourceId,
            String status,
            LocalDate snapshotDate,
            String checksum,
            int rowsRead,
            int rowsSaved
    ) {
        if (dataSourceId == null) {
            return;
        }

        jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET last_status = ?,
                        last_success_at = clock_timestamp(),
                        last_snapshot_date = ?,
                        last_checksum = ?,
                        last_rows_read = ?,
                        last_rows_saved = ?,
                        consecutive_success_count = CASE
                            WHEN ? = 'SUCCEEDED'
                                THEN consecutive_success_count + 1
                            ELSE 0
                        END,
                        consecutive_failure_count = 0,
                        last_error = NULL,
                        updated_at = NOW()
                    WHERE id = ?
                    """)
                .param(1, status)
                .param(2, snapshotDate, Types.DATE)
                .param(3, checksum, Types.VARCHAR)
                .param(4, rowsRead)
                .param(5, rowsSaved)
                .param(6, status)
                .param(7, dataSourceId)
                .update();
    }

    public void markFailed(Long dataSourceId, String errorMessage) {
        if (dataSourceId == null) {
            return;
        }

        jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET last_status = 'FAILED',
                        consecutive_success_count = 0,
                        consecutive_failure_count =
                            consecutive_failure_count + 1,
                        last_error = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """)
                .param(1, abbreviate(errorMessage, 4000), Types.VARCHAR)
                .param(2, dataSourceId)
                .update();
    }

    public List<RetailerDataSourceStatus> findAll() {
        return jdbcClient.sql("""
                        SELECT source.id,
                               retailer.code AS retailer_code,
                               retailer.name AS retailer_name,
                               source.code,
                               source.source_type,
                               source.parser_profile,
                               source.source_url,
                               source.discovery_url,
                               source.price_scope,
                               source.schedule_cron,
                               source.active,
                               source.last_status,
                               source.last_started_at,
                               source.last_success_at,
                               source.last_snapshot_date,
                               source.last_error,
                               source.acknowledged_format_count,
                               source.pending_format_count,
                               source.format_count_flagged_at
                        FROM app.retailer_data_source AS source
                        JOIN app.retailer AS retailer
                          ON retailer.id = source.retailer_id
                        ORDER BY retailer.code, source.code
                        """)
                .query((resultSet, rowNumber) ->
                        new RetailerDataSourceStatus(
                                resultSet.getLong("id"),
                                resultSet.getString("retailer_code"),
                                resultSet.getString("retailer_name"),
                                resultSet.getString("code"),
                                resultSet.getString("source_type"),
                                resultSet.getString("parser_profile"),
                                resultSet.getString("source_url"),
                                resultSet.getString("discovery_url"),
                                resultSet.getString("price_scope"),
                                resultSet.getString("schedule_cron"),
                                resultSet.getBoolean("active"),
                                resultSet.getString("last_status"),
                                resultSet.getTimestamp("last_started_at") == null
                                        ? null
                                        : resultSet.getTimestamp(
                                                "last_started_at"
                                        ).toInstant(),
                                resultSet.getTimestamp("last_success_at") == null
                                        ? null
                                        : resultSet.getTimestamp(
                                                "last_success_at"
                                        ).toInstant(),
                                resultSet.getObject(
                                        "last_snapshot_date",
                                        LocalDate.class
                                ),
                                resultSet.getString("last_error"),
                                (Integer) resultSet.getObject("acknowledged_format_count"),
                                (Integer) resultSet.getObject("pending_format_count"),
                                resultSet.getTimestamp("format_count_flagged_at") == null
                                        ? null
                                        : resultSet.getTimestamp(
                                                "format_count_flagged_at"
                                        ).toInstant()
                        ))
                .list();
    }

    /**
     * Confirms that the source's current distinct-price-format count is a
     * legitimate catalog shape (not a broken/partial feed), so future imports
     * with that same format count are compared normally instead of being
     * flagged again.
     */
    public boolean acknowledgeFormatCount(Long dataSourceId) {
        return jdbcClient.sql("""
                    UPDATE app.retailer_data_source
                    SET acknowledged_format_count = pending_format_count,
                        pending_format_count = NULL,
                        format_count_flagged_at = NULL,
                        updated_at = NOW()
                    WHERE id = ?
                      AND pending_format_count IS NOT NULL
                    """)
                .param(1, dataSourceId)
                .update() == 1;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String abbreviate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }

        return value.substring(0, maximumLength);
    }

    record PriceSource(
            Long id,
            String sourceUrl,
            String discoveryUrl,
            String parserProfile
    ) {
    }
}
