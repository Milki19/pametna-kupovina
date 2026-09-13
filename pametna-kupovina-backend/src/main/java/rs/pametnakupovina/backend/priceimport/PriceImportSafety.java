package rs.pametnakupovina.backend.priceimport;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pre-promotion gates. No writes to catalog or prices are performed here. */
@Component
public class PriceImportSafety {
    private static final int VOLUME_HISTORY_WINDOW = 7;
    private static final int MINIMUM_HISTORY_FOR_VOLUME_CHECK = 3;

    private final JdbcClient jdbc;

    public PriceImportSafety(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public ValidationOutcome validate(long retailerId, Long sourceId, Long storeId,
                         LocalDate date, int selected, int errors, int distinctFormatCount) {
        if (selected <= 0) throw new IllegalStateException("EMPTY_SNAPSHOT: cenovnik nema upotrebljive redove.");
        // Preserve partial-success compatibility, but reject when malformed rows
        // exceed one third of the valid + malformed rows (errors > selected/2).
        if ((long) errors * 2 > selected) throw new IllegalStateException("INVALID_ROWS: " + errors
                + " neispravnih redova; postojeće cene su sačuvane.");
        if (date == null || date.isAfter(LocalDate.now(ZoneId.of("Europe/Belgrade")))) {
            throw new IllegalStateException("FUTURE_SNAPSHOT: datum cenovnika nije prihvatljiv.");
        }
        LocalDate latestActive = jdbc.sql("""
                SELECT MAX(o.price_date) AS latest
                FROM app.current_price_offer o
                JOIN app.retailer_product p ON p.id=o.retailer_product_id
                WHERE p.retailer_id=? AND o.store_id IS NOT DISTINCT FROM ?::BIGINT
                """).param(1, retailerId).param(2, storeId, Types.BIGINT)
                .query((rs, n) -> rs.getObject("latest", LocalDate.class))
                .optional().orElse(null);
        if (latestActive != null && date.isBefore(latestActive)) {
            throw new IllegalStateException("OLDER_THAN_CURRENT: novi fajl je stariji od aktivnog cenovnika " + latestActive);
        }
        Limits limits = sourceId == null ? new Limits(1, new BigDecimal("0.5")) : jdbc.sql("""
                SELECT COALESCE(expected_min_rows_saved,1) AS minimum, minimum_volume_ratio
                FROM app.retailer_data_source WHERE id=?
                """).param(sourceId).query((rs,n) -> new Limits(rs.getInt("minimum"),
                        rs.getBigDecimal("minimum_volume_ratio"))).single();
        if (selected < limits.minimum()) {
            throw new IllegalStateException("BELOW_MINIMUM: " + selected + " redova, minimum " + limits.minimum());
        }
        return requireVolume(retailerId, sourceId, storeId, selected, distinctFormatCount, limits.ratio());
    }

    /**
     * Compares the new row count against the MEDIAN of the last
     * {@value VOLUME_HISTORY_WINDOW} accepted imports for this exact
     * retailer/source/store, instead of the live current_price_offer state.
     * A single anomalous accepted import can no longer drag the floor down on
     * its own the way a "last value" baseline would (a genuine risk once we
     * track 30-40 live-ish stores instead of a handful).
     *
     * When the drop is real but the source's distinct price-format count also
     * changed from what was last acknowledged, this is treated as a possible
     * catalog restructuring (e.g. IDEA/Roda moving from eight to three price
     * formats): the import is accepted but flagged for one manual review
     * instead of silently lowering the bar or rejecting a legitimate feed.
     */
    private ValidationOutcome requireVolume(long retailerId, Long sourceId, Long storeId,
                                             long selected, int distinctFormatCount, BigDecimal ratio) {
        List<Integer> history = recentRowCounts(retailerId, sourceId, storeId);
        if (history.size() < MINIMUM_HISTORY_FOR_VOLUME_CHECK) {
            return ValidationOutcome.ok();
        }
        long median = median(history);
        if (median == 0 || BigDecimal.valueOf(selected).compareTo(BigDecimal.valueOf(median).multiply(ratio)) >= 0) {
            return ValidationOutcome.ok();
        }
        if (sourceId == null) {
            throw new IllegalStateException("VOLUME_DROP: " + selected + " ponuda prema medijani poslednjih "
                    + history.size() + " uvoza (" + median + "); postojeće cene su sačuvane.");
        }
        Integer acknowledgedFormats = jdbc.sql(
                "SELECT acknowledged_format_count FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(Integer.class).optional().orElse(null);
        if (acknowledgedFormats != null && acknowledgedFormats == distinctFormatCount) {
            throw new IllegalStateException("VOLUME_DROP: " + selected + " ponuda prema medijani poslednjih "
                    + history.size() + " uvoza (" + median + "); postojeće cene su sačuvane.");
        }
        flagFormatChange(sourceId, distinctFormatCount);
        return ValidationOutcome.formatReviewPending();
    }

    private List<Integer> recentRowCounts(long retailerId, Long sourceId, Long storeId) {
        return jdbc.sql("""
                SELECT rows_saved FROM app.import_run
                WHERE retailer_id=? AND data_source_id IS NOT DISTINCT FROM ?::BIGINT
                  AND store_id IS NOT DISTINCT FROM ?::BIGINT
                  AND status IN ('SUCCEEDED','SUCCEEDED_WITH_ERRORS')
                ORDER BY id DESC LIMIT ?
                """).param(1, retailerId).param(2, sourceId, Types.BIGINT).param(3, storeId, Types.BIGINT)
                .param(4, VOLUME_HISTORY_WINDOW)
                .query(Integer.class).list();
    }

    private void flagFormatChange(long sourceId, int distinctFormatCount) {
        jdbc.sql("""
                UPDATE app.retailer_data_source
                SET pending_format_count=?, format_count_flagged_at=NOW(), updated_at=NOW()
                WHERE id=?
                """).param(1, distinctFormatCount).param(2, sourceId).update();
    }

    static long median(List<Integer> values) {
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        return size % 2 == 1
                ? sorted.get(size / 2)
                : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2L;
    }

    /** Use unique saved offers too: duplicate CSV rows must not bypass the volume gate. */
    public ValidationOutcome validateWrittenVolume(long retailerId, Long sourceId, Long storeId, long runId) {
        long written = jdbc.sql("SELECT COUNT(*) FROM app.current_price_offer WHERE import_run_id=?")
                .param(runId).query(Long.class).single();
        BigDecimal ratio = sourceId == null ? new BigDecimal("0.5") : jdbc.sql(
                "SELECT minimum_volume_ratio FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(BigDecimal.class).single();
        int distinctFormatCount = jdbc.sql("""
                SELECT COUNT(DISTINCT retailer_format_name) FROM app.current_price_offer
                WHERE import_run_id=?
                """).param(runId).query(Integer.class).single();
        ValidationOutcome outcome = requireVolume(retailerId, sourceId, storeId, written, distinctFormatCount, ratio);
        int minimum = sourceId == null ? 1 : jdbc.sql(
                "SELECT COALESCE(expected_min_rows_saved,1) FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(Integer.class).single();
        if (written < minimum) throw new IllegalStateException("BELOW_MINIMUM: premalo jedinstvenih ponuda: " + written);
        if (written == 0) throw new IllegalStateException("EMPTY_SNAPSHOT: nije upisana nijedna ponuda.");
        return outcome;
    }

    public record ValidationOutcome(boolean needsFormatReview) {
        static ValidationOutcome ok() {
            return new ValidationOutcome(false);
        }

        static ValidationOutcome formatReviewPending() {
            return new ValidationOutcome(true);
        }
    }

    private record Limits(int minimum, BigDecimal ratio) {}
}
