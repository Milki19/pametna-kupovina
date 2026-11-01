package rs.pametnakupovina.backend.webtiming;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.account.RequestCounter;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Merenje u pregledaču (E2): web verzija javi koliko je kupac čekao na
 * otvaranje, korak 1, preporuku i pretragu. Ide samo našem serveru, bez
 * naloga, adrese i sadržaja spiska: ime merenja, trajanje i verzija. Server
 * meri svoje vreme, a ovo je vreme sa mrežom, kako ga vidi telefon.
 */
@RestController
public class WebTimingController {

    static final Set<String> METRICS = Set.of("prvi-odgovor", "otvaranje", "korak1", "preporuka", "pretraga");
    static final int MOST_PER_REPORT = 20;
    static final int REPORTS_PER_MINUTE = 20;
    private static final int LONGEST_MS = 600_000;

    private final JdbcClient jdbcClient;
    private final RequestCounter counter;

    public WebTimingController(JdbcClient jdbcClient, RequestCounter counter) {
        this.jdbcClient = jdbcClient;
        this.counter = counter;
    }

    @PostMapping("/api/v1/web-timings")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void report(@RequestBody WebTimingReport report, HttpServletRequest request) {
        if (report == null || report.timings() == null || report.timings().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Merenje je prazno.");
        }
        if (report.timings().size() > MOST_PER_REPORT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Najviše " + MOST_PER_REPORT + " merenja odjednom.");
        }
        // Javno je, pa jedna mreža ne sme da puni tabelu: pregledač šalje
        // jednom kad se kartica skloni ili kad se skupi 20 merenja.
        if (counter.countAndGet("web-timing:" + request.getRemoteAddr(), Duration.ofMinutes(1)) > REPORTS_PER_MINUTE) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Previše merenja u kratkom roku.");
        }
        String version = version(report.appVersion());
        List<WebTiming> kept = report.timings().stream()
                .filter(t -> t != null && t.metric() != null && METRICS.contains(t.metric())
                        && t.durationMs() != null && t.durationMs() >= 0 && t.durationMs() <= LONGEST_MS)
                .toList();
        if (kept.isEmpty()) {
            return;
        }

        // Stariji od 90 dana odlaze usput, bez posebnog zakazanog posla.
        jdbcClient.sql("DELETE FROM app.web_timing WHERE created_at < NOW() - INTERVAL '90 days'").update();
        for (WebTiming timing : kept) {
            jdbcClient.sql("INSERT INTO app.web_timing (app_version, metric, duration_ms) VALUES (?, ?, ?)")
                    .params(version, timing.metric(), (int) Math.round(timing.durationMs()))
                    .update();
        }
    }

    /** Za nedeljni izveštaj: medijana, p75 i p95 po merenju i verziji. */
    @GetMapping("/api/v1/imports/quality/web-timings")
    public List<WebTimingSummary> summary(@RequestParam(defaultValue = "7") int days) {
        return jdbcClient.sql("""
                        SELECT metric,
                               app_version,
                               COUNT(*) AS times,
                               PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY duration_ms) AS p50,
                               PERCENTILE_DISC(0.75) WITHIN GROUP (ORDER BY duration_ms) AS p75,
                               PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95
                        FROM app.web_timing
                        WHERE created_at >= NOW() - MAKE_INTERVAL(days => ?)
                        GROUP BY metric, app_version
                        ORDER BY metric, app_version DESC
                        """)
                .param(Math.max(1, Math.min(days, 90)))
                .query((row, n) -> new WebTimingSummary(
                        row.getString("metric"),
                        row.getString("app_version"),
                        row.getInt("times"),
                        row.getInt("p50"),
                        row.getInt("p75"),
                        row.getInt("p95")
                ))
                .list();
    }

    private static String version(String value) {
        if (value == null || !value.strip().matches("[0-9A-Za-z.\\-]{1,20}")) {
            return "?";
        }
        return value.strip();
    }

    public record WebTimingReport(String appVersion, List<WebTiming> timings) {
    }

    public record WebTiming(String metric, Double durationMs) {
    }

    public record WebTimingSummary(String metric, String appVersion, int times, int p50Ms, int p75Ms, int p95Ms) {
    }
}
