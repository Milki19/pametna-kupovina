package rs.pametnakupovina.backend.priceimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.priceimport.maxi.MaxiPriceImportCoordinator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One sequential cycle for the approved price sources; never enables new retailers. */
@Service
public class DailyPriceRefreshService {
    private static final Logger log = LoggerFactory.getLogger(DailyPriceRefreshService.class);
    private static final ZoneId ZONE = ZoneId.of("Europe/Belgrade");
    /** The five sources the freshness rule is about. */
    static final List<String> CORE = List.of("LIDL", "EUROPROM", "IDEA_RODA", "UNIVEREXPORT", "MAXI");
    /** Delhaize's chain-wide list for Maxi, beside Maxi's own store files. */
    static final String DELHAIZE_CATALOG = "MAXI_CATALOG";
    /**
     * Chains that publish one chain-wide list, often days apart: METRO, Super
     * Vero and Delhaize's catalogue. They were imported by hand, so their
     * prices went stale (Super Vero 11.09., METRO 13.09.). They run after the
     * core five; a failure warns but does not fail the day, and a list the
     * chain has not replaced yet is not a warning.
     */
    static final List<String> EXTRA = List.of(DELHAIZE_CATALOG, "METRO", "VEROPOULOS");
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final PriceImportService imports;
    private final MaxiPriceImportCoordinator maxi;
    private final ImportRunRecovery recovery;

    public DailyPriceRefreshService(JdbcTemplate jdbc, DataSource dataSource,
                                   PriceImportService imports, MaxiPriceImportCoordinator maxi,
                                   ImportRunRecovery recovery) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.imports = imports;
        this.maxi = maxi;
        this.recovery = recovery;
    }

    public Map<String, Object> refresh(boolean manual) {
        // Dedicated session lock survives individual import transactions and is released on process death.
        // Always explicitly unlock before returning a pooled connection.
        try (Connection connection = dataSource.getConnection()) {
            try (var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT pg_try_advisory_lock(134712,1)")) {
                result.next();
                if (!result.getBoolean(1)) return Map.of("status", "ALREADY_RUNNING");
            }
            try {
                // Owning this lock proves a preceding RUNNING cycle no longer has a coordinator.
                jdbc.update("UPDATE app.price_refresh_cycle SET status='FAILED', finished_at=now() WHERE status='RUNNING'");
                recovery.recover();
                LocalDate today = LocalDate.now(ZONE);
                if (!manual && !due(today)) return Map.of("status", "NOT_DUE");
                long id = jdbc.queryForObject("""
                        INSERT INTO app.price_refresh_cycle(cycle_date,status) VALUES (?,'RUNNING') RETURNING id
                        """, Long.class, today);
                boolean failed = false;
                boolean warning = false;
                try {
                    for (String code : java.util.stream.Stream.concat(CORE.stream(), EXTRA.stream()).toList()) {
                        Outcome outcome;
                        try {
                            outcome = importRetailer(code, today);
                        } catch (RuntimeException exception) {
                            log.error("Daily refresh {}: {} failed", id, code, exception);
                            outcome = new Outcome(null, 0, "FAILED", "Uvoz nije završen; detalji u import_run i serverskom logu.");
                        }
                        jdbc.update("""
                                INSERT INTO app.price_refresh_result(cycle_id,retailer_code,snapshot_date,rows_saved,status,detail)
                                VALUES (?,?,?,?,?,?)
                                """, id, code, outcome.date(), outcome.rows(), outcome.status(), outcome.detail());
                        failed |= failsTheDay(code, outcome.status());
                        warning |= warnsTheDay(code, outcome.status());
                        log.info("Daily refresh {}: {} {} date={} rows={}", id, code, outcome.status(), outcome.date(), outcome.rows());
                    }
                    String status = failed ? "FAILED" : warning ? "WARNING" : "SUCCEEDED";
                    jdbc.update("UPDATE app.price_refresh_cycle SET status=?, finished_at=now() WHERE id=?", status, id);
                    log.info("Daily refresh {} completed: {}", id, status);
                    return Map.of("cycleId", id, "status", status);
                } catch (RuntimeException exception) {
                    jdbc.update("UPDATE app.price_refresh_cycle SET status='FAILED', finished_at=now() WHERE id=?", id);
                    throw exception;
                }
            } finally {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(134712,1)");
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Daily refresh coordination failed", exception);
        }
    }

    private boolean due(LocalDate today) {
        if (ZonedDateTime.now(ZONE).getHour() < 8) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) < 3
                  AND COUNT(*) FILTER (WHERE status='SUCCEEDED')=0
                  AND COALESCE(MAX(started_at) < now()-interval '2 hours',true)
                FROM app.price_refresh_cycle WHERE cycle_date=?
                """, Boolean.class, today));
    }

    private Outcome importRetailer(String code, LocalDate today) {
        if (EXTRA.contains(code)) {
            ImportResult result = imports.importPrices(code.equals(DELHAIZE_CATALOG) ? "MAXI" : code);
            return new Outcome(result.snapshotDate(), result.rowsSaved(), classifyExtra(result.status()),
                    result.status() + "; cenovnik od " + result.snapshotDate());
        }
        if (!code.equals("MAXI")) {
            ImportResult result = imports.importPrices(code);
            return new Outcome(result.snapshotDate(), result.rowsSaved(),
                    classify(result.status(), result.snapshotDate(), today), result.status());
        }
        var result = maxi.importLatest();
        boolean allClean = result.filesFound() == 6 && result.storesImported() == 6
                && result.stores().stream().allMatch(store -> store.importResult() != null
                && store.importResult().status().equals("SUCCEEDED"));
        int rows = result.stores().stream().filter(store -> store.importResult() != null)
                .mapToInt(store -> store.importResult().rowsSaved()).sum();
        String status = result.storesImported() == 0 ? "FAILED" : allClean ? "SUCCEEDED" : "SUCCEEDED_WITH_ERRORS";
        return new Outcome(result.snapshotDate(), rows, classify(status, result.snapshotDate(), today),
                result.storesImported() + "/6 potvrđenih Maxi objekata; " + status);
    }

    static String classify(String status, LocalDate snapshot, LocalDate today) {
        if (status.equals("FAILED")) return "FAILED";
        return status.equals("SUCCEEDED") && snapshot != null
                && !snapshot.isBefore(today.minusDays(1)) && !snapshot.isAfter(today) ? "SUCCEEDED" : "WARNING";
    }

    /** A chain-wide list is as fresh as the chain publishes it. */
    static String classifyExtra(String importStatus) {
        if (importStatus.equals("FAILED")) return "FAILED";
        return importStatus.equals("SUCCEEDED") ? "SUCCEEDED" : "WARNING";
    }

    static boolean failsTheDay(String code, String status) {
        return !EXTRA.contains(code) && status.equals("FAILED");
    }

    static boolean warnsTheDay(String code, String status) {
        return status.equals("WARNING") || (EXTRA.contains(code) && status.equals("FAILED"));
    }

    static int consecutiveDays(Set<LocalDate> successfulDays, LocalDate today) {
        LocalDate day = successfulDays.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (successfulDays.contains(day)) { count++; day = day.minusDays(1); }
        return count;
    }

    public Map<String, Object> status() {
        Set<LocalDate> days = new HashSet<>(jdbc.query("""
                SELECT DISTINCT cycle_date FROM app.price_refresh_cycle WHERE status='SUCCEEDED'
                """, (rs, n) -> rs.getObject(1, LocalDate.class)));
        return Map.of("consecutiveSuccessfulDays", consecutiveDays(days, LocalDate.now(ZONE)),
                "requiredDays", 7,
                "freshnessRule", "Svih pet osnovnih lanaca bez grešaka; cenovnik od danas ili juče. "
                        + "METRO, Super Vero i Delhaize katalog idu posle njih; njihova greška je upozorenje.",
                "cycles", jdbc.queryForList("""
                        SELECT id,cycle_date::text AS cycle_date,started_at,finished_at,status
                        FROM app.price_refresh_cycle ORDER BY id DESC LIMIT 21
                        """),
                "results", jdbc.queryForList("""
                        SELECT cycle_id,retailer_code,snapshot_date::text AS snapshot_date,rows_saved,status,detail,finished_at
                        FROM app.price_refresh_result WHERE cycle_id IN
                        (SELECT id FROM app.price_refresh_cycle ORDER BY id DESC LIMIT 21)
                        ORDER BY cycle_id DESC,retailer_code
                        """));
    }

    private record Outcome(LocalDate date, int rows, String status, String detail) {}
}
