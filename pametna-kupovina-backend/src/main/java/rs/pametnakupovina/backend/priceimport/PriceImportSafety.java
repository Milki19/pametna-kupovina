package rs.pametnakupovina.backend.priceimport;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;

/** Pre-promotion gates. No writes to catalog or prices are performed here. */
@Component
public class PriceImportSafety {
    private final JdbcClient jdbc;

    public PriceImportSafety(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(long retailerId, Long sourceId, Long storeId,
                         LocalDate date, int selected, int errors) {
        if (selected <= 0) throw new IllegalStateException("EMPTY_SNAPSHOT: cenovnik nema upotrebljive redove.");
        // Preserve partial-success compatibility, but reject when malformed rows
        // exceed one third of the valid + malformed rows (errors > selected/2).
        if ((long) errors * 2 > selected) throw new IllegalStateException("INVALID_ROWS: " + errors
                + " neispravnih redova; postojeće cene su sačuvane.");
        if (date == null || date.isAfter(LocalDate.now(ZoneId.of("Europe/Belgrade")))) {
            throw new IllegalStateException("FUTURE_SNAPSHOT: datum cenovnika nije prihvatljiv.");
        }
        var baseline = jdbc.sql("""
                SELECT COUNT(*) AS offers, COUNT(DISTINCT o.retailer_product_id) AS products,
                       MAX(o.price_date) AS latest
                FROM app.current_price_offer o
                JOIN app.retailer_product p ON p.id=o.retailer_product_id
                WHERE p.retailer_id=? AND o.store_id IS NOT DISTINCT FROM ?::BIGINT
                """).param(1, retailerId).param(2, storeId, Types.BIGINT)
                .query((rs, n) -> new Baseline(rs.getLong("offers"), rs.getLong("products"),
                        rs.getObject("latest", LocalDate.class)))
                .single();
        if (baseline.latest() != null && date.isBefore(baseline.latest())) {
            throw new IllegalStateException("OLDER_THAN_CURRENT: novi fajl je stariji od aktivnog cenovnika " + baseline.latest());
        }
        var limits = sourceId == null ? new Limits(1, new BigDecimal("0.5")) : jdbc.sql("""
                SELECT COALESCE(expected_min_rows_saved,1) AS minimum, minimum_volume_ratio
                FROM app.retailer_data_source WHERE id=?
                """).param(sourceId).query((rs,n) -> new Limits(rs.getInt("minimum"),
                        rs.getBigDecimal("minimum_volume_ratio"))).single();
        if (selected < limits.minimum()) {
            throw new IllegalStateException("BELOW_MINIMUM: " + selected + " redova, minimum " + limits.minimum());
        }
        requireVolume(selected, baseline.offers(), baseline.products(), limits.ratio());
    }

    static void requireVolume(long selected, long baseline, BigDecimal ratio) {
        requireVolume(selected, baseline, baseline, ratio);
    }

    /**
     * A source may legitimately publish fewer store-format scopes than its
     * previous file (IDEA/Roda changed from eight to three). In that case the
     * product-level floor still protects against an almost-empty feed without
     * rejecting a valid scope migration.
     */
    static void requireVolume(long selected, long baseline, long distinctProducts, BigDecimal ratio) {
        if (BigDecimal.valueOf(selected).compareTo(BigDecimal.valueOf(baseline).multiply(ratio)) < 0) {
            if (BigDecimal.valueOf(selected).compareTo(BigDecimal.valueOf(distinctProducts).multiply(ratio)) >= 0) return;
            throw new IllegalStateException("VOLUME_DROP: " + selected + " ponuda prema prethodnih " + baseline
                    + " (" + distinctProducts + " proizvoda); postojeće cene su sačuvane.");
        }
    }

    /** Use unique saved offers too: duplicate CSV rows must not bypass the volume gate. */
    public void validateWrittenVolume(long retailerId, Long sourceId, Long storeId, long runId, long before) {
        long written = jdbc.sql("SELECT COUNT(*) FROM app.current_price_offer WHERE import_run_id=?")
                .param(runId).query(Long.class).single();
        BigDecimal ratio = sourceId == null ? new BigDecimal("0.5") : jdbc.sql(
                "SELECT minimum_volume_ratio FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(BigDecimal.class).single();
        long distinctProducts = jdbc.sql("SELECT COUNT(DISTINCT o.retailer_product_id) FROM app.current_price_offer o "
                        + "JOIN app.import_run i ON i.id=o.import_run_id WHERE i.id=?")
                .param(runId).query(Long.class).single();
        requireVolume(written, before, distinctProducts, ratio);
        int minimum = sourceId == null ? 1 : jdbc.sql(
                "SELECT COALESCE(expected_min_rows_saved,1) FROM app.retailer_data_source WHERE id=?")
                .param(sourceId).query(Integer.class).single();
        if (written < minimum) throw new IllegalStateException("BELOW_MINIMUM: premalo jedinstvenih ponuda: " + written);
        if (written == 0) throw new IllegalStateException("EMPTY_SNAPSHOT: nije upisana nijedna ponuda.");
    }

    public long currentOfferCount(long retailerId, Long storeId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM app.current_price_offer o
                JOIN app.retailer_product p ON p.id=o.retailer_product_id
                WHERE p.retailer_id=? AND o.store_id IS NOT DISTINCT FROM ?::BIGINT
                """).param(1, retailerId).param(2, storeId, Types.BIGINT).query(Long.class).single();
    }

    private record Baseline(long offers, long products, LocalDate latest) {}
    private record Limits(int minimum, BigDecimal ratio) {}
}
