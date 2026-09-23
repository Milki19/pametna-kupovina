package rs.pametnakupovina.backend.crash;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * Pad na tuđem telefonu je inače nevidljiv. Aplikacija ga zapamti i pošalje
 * pri sledećem pokretanju — samo tehnički zapis greške, verziju i model
 * telefona, bez broja uređaja — a vlasnik ga vidi na /admin.
 */
@RestController
public class CrashReportController {

    private static final int LONGEST_TRACE = 20_000;

    private final JdbcClient jdbcClient;

    public CrashReportController(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostMapping("/api/v1/crash-reports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestBody CrashReport report) {
        if (report == null || report.stackTrace() == null || report.stackTrace().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Izveštaj o padu je prazan.");
        }

        // Stariji od 90 dana odlaze usput, bez posebnog zakazanog posla.
        jdbcClient.sql("DELETE FROM app.crash_report WHERE created_at < NOW() - INTERVAL '90 days'")
                .update();
        jdbcClient.sql("""
                        INSERT INTO app.crash_report (app_version, android_version, device, stack_trace)
                        VALUES (?, ?, ?, ?)
                        """)
                .params(
                        cut(report.appVersion(), 20),
                        cut(report.androidVersion(), 20),
                        cut(report.device(), 100),
                        cut(report.stackTrace(), LONGEST_TRACE)
                )
                .update();
    }

    /** Za /admin: isti pad u istoj verziji jednom, sa brojem ponavljanja. */
    @GetMapping("/api/v1/imports/quality/crashes")
    public List<CrashGroup> crashes() {
        return jdbcClient.sql("""
                        SELECT SPLIT_PART(stack_trace, CHR(10), 1) AS problem,
                               app_version,
                               COUNT(*) AS times,
                               MAX(created_at) AS last_seen,
                               (ARRAY_AGG(device || ', Android ' || android_version
                                          ORDER BY created_at DESC))[1] AS phone,
                               (ARRAY_AGG(stack_trace ORDER BY created_at DESC))[1] AS latest
                        FROM app.crash_report
                        GROUP BY 1, 2
                        ORDER BY last_seen DESC
                        LIMIT 50
                        """)
                .query((row, number) -> new CrashGroup(
                        row.getString("problem"),
                        row.getString("app_version"),
                        row.getInt("times"),
                        row.getObject("last_seen", java.time.OffsetDateTime.class).toInstant(),
                        row.getString("phone"),
                        row.getString("latest")
                ))
                .list();
    }

    private static String cut(String value, int longest) {
        String text = value == null || value.isBlank() ? "?" : value.strip();
        return text.length() <= longest ? text : text.substring(0, longest);
    }

    public record CrashReport(
            String appVersion,
            String androidVersion,
            String device,
            String stackTrace
    ) {
    }

    public record CrashGroup(
            String problem,
            String appVersion,
            int times,
            Instant lastSeen,
            String phone,
            String latest
    ) {
    }
}
