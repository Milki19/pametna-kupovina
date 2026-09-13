package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression coverage for the IDEA/Roda VOLUME_DROP incident: the original
 * escape hatch compared the new file against the LIVE current_price_offer
 * state, which is itself the output of the previous import. One accepted
 * anomaly could permanently lower the floor for every import after it
 * (see ficaFromSep12.md). These tests pin down the replacement behaviour:
 * a rolling median over accepted import_run history, with a genuine
 * distinct-format-count change as the only way to bypass a drop.
 */
@SpringBootTest(properties = {"price-import.http.request-timeout-seconds=1"})
@Testcontainers
class PriceImportSafetyTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private PriceImportSafety safety;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private RetailerDataSourceRepository dataSourceRepository;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Europe/Belgrade"));

    @Test
    void staticMedianHandlesOddAndEvenWindows() {
        assertThat(PriceImportSafety.median(List.of(10, 30, 20))).isEqualTo(20);
        assertThat(PriceImportSafety.median(List.of(10, 20, 30, 40))).isEqualTo(25);
        assertThat(PriceImportSafety.median(List.of(5))).isEqualTo(5);
    }

    @Test
    void withoutEnoughHistoryVolumeDropIsNotEnforced() {
        long retailerId = insertRetailer("SAFETY_BOOTSTRAP");
        Long sourceId = insertDataSource(retailerId, "SAFETY_BOOTSTRAP_SRC", null);
        // Only two prior accepted runs: below the 3-run minimum, so a very
        // low row count must not be rejected as a volume drop yet.
        seedImportRun(retailerId, sourceId, null, 100, "SUCCEEDED");
        seedImportRun(retailerId, sourceId, null, 100, "SUCCEEDED");

        PriceImportSafety.ValidationOutcome outcome =
                safety.validate(retailerId, sourceId, null, TODAY, 5, 0, 1);

        assertThat(outcome.needsFormatReview()).isFalse();
    }

    @Test
    void rejectsGenuineDropWhenFormatShapeIsUnchanged() {
        long retailerId = insertRetailer("SAFETY_GENUINE_DROP");
        Long sourceId = insertDataSource(retailerId, "SAFETY_GENUINE_DROP_SRC", 1);
        for (int i = 0; i < 7; i++) {
            seedImportRun(retailerId, sourceId, null, 100, "SUCCEEDED");
        }

        assertThatThrownBy(() ->
                safety.validate(retailerId, sourceId, null, TODAY, 30, 0, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("VOLUME_DROP");
    }

    @Test
    void acceptsAndFlagsReviewWhenFormatShapeChanges() {
        long retailerId = insertRetailer("SAFETY_FORMAT_CHANGE");
        // Acknowledged shape was 8 price formats (like IDEA/Roda before its change).
        Long sourceId = insertDataSource(retailerId, "SAFETY_FORMAT_CHANGE_SRC", 8);
        for (int i = 0; i < 7; i++) {
            seedImportRun(retailerId, sourceId, null, 100, "SUCCEEDED");
        }

        PriceImportSafety.ValidationOutcome outcome =
                safety.validate(retailerId, sourceId, null, TODAY, 30, 0, 3);

        assertThat(outcome.needsFormatReview()).isTrue();
        assertThat(pendingFormatCount(sourceId)).isEqualTo(3);
        assertThat(acknowledgedFormatCount(sourceId)).isEqualTo(8);

        boolean acknowledged = dataSourceRepository.acknowledgeFormatCount(sourceId);
        assertThat(acknowledged).isTrue();
        assertThat(acknowledgedFormatCount(sourceId)).isEqualTo(3);
        assertThat(pendingFormatCount(sourceId)).isNull();
    }

    @Test
    void oneAcceptedAnomalyDoesNotRatchetTheFloorDown() {
        long retailerId = insertRetailer("SAFETY_RATCHET");
        Long sourceId = insertDataSource(retailerId, "SAFETY_RATCHET_SRC", 1);
        // Six normal days plus one already-accepted anomaly at 20 rows.
        // Under the OLD "last value" baseline this single anomaly would have
        // become the new floor. The median must stay anchored near 100.
        for (int i = 0; i < 6; i++) {
            seedImportRun(retailerId, sourceId, null, 100, "SUCCEEDED");
        }
        seedImportRun(retailerId, sourceId, null, 20, "SUCCEEDED");

        assertThatThrownBy(() ->
                safety.validate(retailerId, sourceId, null, TODAY, 30, 0, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("VOLUME_DROP");
    }

    private long insertRetailer(String code) {
        return jdbcClient.sql("""
                        INSERT INTO app.retailer (code, name)
                        VALUES (?, ?)
                        RETURNING id
                        """)
                .param(1, code)
                .param(2, code + " test")
                .query(Long.class)
                .single();
    }

    private Long insertDataSource(long retailerId, String code, Integer acknowledgedFormatCount) {
        return jdbcClient.sql("""
                        INSERT INTO app.retailer_data_source (
                            retailer_id, code, source_type, parser_profile,
                            source_url, active, last_status, minimum_volume_ratio,
                            acknowledged_format_count
                        )
                        VALUES (?, ?, 'PRICE_CATALOG', 'TEST',
                            'https://example.test/prices.csv', TRUE, 'SUCCEEDED', 0.5, ?)
                        RETURNING id
                        """)
                .param(1, retailerId)
                .param(2, code)
                .param(3, acknowledgedFormatCount, Types.INTEGER)
                .query(Long.class)
                .single();
    }

    private void seedImportRun(long retailerId, Long sourceId, Long storeId, int rowsSaved, String status) {
        jdbcClient.sql("""
                    INSERT INTO app.import_run (
                        retailer_id, data_source_id, store_id, source_url,
                        status, rows_read, rows_selected, rows_saved
                    )
                    VALUES (?, ?, ?, 'https://example.test/prices.csv', ?, ?, ?, ?)
                    """)
                .param(1, retailerId)
                .param(2, sourceId, Types.BIGINT)
                .param(3, storeId, Types.BIGINT)
                .param(4, status)
                .param(5, rowsSaved)
                .param(6, rowsSaved)
                .param(7, rowsSaved)
                .update();
    }

    private Integer pendingFormatCount(Long sourceId) {
        return jdbcClient.sql(
                "SELECT pending_format_count FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(Integer.class).optional().orElse(null);
    }

    private Integer acknowledgedFormatCount(Long sourceId) {
        return jdbcClient.sql(
                "SELECT acknowledged_format_count FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(Integer.class).optional().orElse(null);
    }
}
