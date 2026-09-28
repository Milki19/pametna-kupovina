package rs.pametnakupovina.backend.priceimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One sequential cycle a day for each market's approved price sources, on
 * the market's own clock; never enables new retailers. Markets without
 * price sources are skipped.
 */
@Service
public class DailyPriceRefreshService {
    private static final Logger log = LoggerFactory.getLogger(DailyPriceRefreshService.class);
    private static final int LOCK_CLASS = 134712;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Map<String, MarketPriceSources> sources;
    private final ImportRunRecovery recovery;
    private final MarketRepository markets;

    public DailyPriceRefreshService(JdbcTemplate jdbc, DataSource dataSource,
                                    List<MarketPriceSources> sources,
                                    ImportRunRecovery recovery, MarketRepository markets) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        // Two sets of sources for one market would run one of them by bean
        // order, so that refuses to start.
        this.sources = sources.stream().collect(Collectors.toUnmodifiableMap(
                MarketPriceSources::marketCode, Function.identity()));
        this.recovery = recovery;
        this.markets = markets;
    }

    /**
     * Every market with price sources, one after another. With one market
     * the answer is that market's cycle, as it always was.
     */
    public Map<String, Object> refresh(boolean manual) {
        Map<String, Map<String, Object>> byMarket = new LinkedHashMap<>();
        for (Market market : markets.active()) {
            MarketPriceSources marketSources = sources.get(market.code());
            if (marketSources != null) byMarket.put(market.code(), refresh(market, marketSources, manual));
        }
        if (byMarket.size() == 1) return byMarket.values().iterator().next();
        return Map.of("markets", byMarket);
    }

    public Map<String, Object> refresh(String marketCode, boolean manual) {
        Market market = market(marketCode);
        return refresh(market, sourcesOf(market), manual);
    }

    private Map<String, Object> refresh(Market market, MarketPriceSources marketSources, boolean manual) {
        // Dedicated session lock survives individual import transactions and is released on process death.
        // Always explicitly unlock before returning a pooled connection. One lock per market.
        try (Connection connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?,?)")) {
                statement.setInt(1, LOCK_CLASS);
                statement.setInt(2, market.id());
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) return Map.of("status", "ALREADY_RUNNING");
                }
            }
            try {
                // Owning this lock proves a preceding RUNNING cycle of this market no longer has a coordinator.
                jdbc.update("""
                        UPDATE app.price_refresh_cycle SET status='FAILED', finished_at=now()
                        WHERE status='RUNNING' AND market_id=?
                        """, market.id());
                recovery.recover();
                LocalDate today = market.today();
                List<ChainPriceSource> chains = marketSources.chains();
                if (!manual && !due(market, today, requiredCodes(chains))) return Map.of("status", "NOT_DUE");
                long id = jdbc.queryForObject("""
                        INSERT INTO app.price_refresh_cycle(market_id,cycle_date,status) VALUES (?,?,'RUNNING') RETURNING id
                        """, Long.class, market.id(), today);
                boolean failed = false;
                boolean warning = false;
                try {
                    for (ChainPriceSource chain : chains) {
                        String code = chain.code();
                        ChainRefreshOutcome outcome;
                        try {
                            outcome = chain.refresh(today);
                        } catch (RuntimeException exception) {
                            log.error("Daily refresh {}: {} failed", id, code, exception);
                            outcome = new ChainRefreshOutcome(null, 0, "FAILED", "Uvoz nije završen; detalji u import_run i serverskom logu.");
                        }
                        jdbc.update("""
                                INSERT INTO app.price_refresh_result(cycle_id,retailer_code,snapshot_date,rows_saved,status,detail)
                                VALUES (?,?,?,?,?,?)
                                """, id, code, outcome.snapshotDate(), outcome.rows(), outcome.status(), outcome.detail());
                        failed |= ChainRefreshOutcome.failsTheDay(chain.required(), outcome.status());
                        warning |= ChainRefreshOutcome.warnsTheDay(chain.required(), outcome.status());
                        log.info("Daily refresh {} ({}): {} {} date={} rows={}", id, market.code(), code,
                                outcome.status(), outcome.snapshotDate(), outcome.rows());
                    }
                    String status = failed ? "FAILED" : warning ? "WARNING" : "SUCCEEDED";
                    jdbc.update("UPDATE app.price_refresh_cycle SET status=?, finished_at=now() WHERE id=?", status, id);
                    log.info("Daily refresh {} ({}) completed: {}", id, market.code(), status);
                    return Map.of("cycleId", id, "status", status);
                } catch (RuntimeException exception) {
                    jdbc.update("UPDATE app.price_refresh_cycle SET status='FAILED', finished_at=now() WHERE id=?", id);
                    throw exception;
                }
            } finally {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_unlock(?,?)")) {
                    statement.setInt(1, LOCK_CLASS);
                    statement.setInt(2, market.id());
                    statement.execute();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Daily refresh coordination failed", exception);
        }
    }

    /** From eight in the morning where the market is, at most three tries a day, two hours apart. */
    private boolean due(Market market, LocalDate today, List<String> requiredCodes) {
        if (ZonedDateTime.now(market.timeZone()).getHour() < 8) return false;
        return retryWanted(market, today, requiredCodes);
    }

    /**
     * Another try helps only while a required chain is short of clean: a
     * retry can pick up the list a chain publishes late. A day that warns
     * only because of a chain that may not fail it (a stale chain-wide list,
     * one bad row in a portal chain's file) comes out the same on every try,
     * so it counts as done.
     */
    public boolean retryWanted(Market market, LocalDate today, List<String> requiredCodes) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COUNT(*) < 3
                  AND COUNT(*) FILTER (WHERE cycle.status='SUCCEEDED'
                      OR (cycle.status='WARNING' AND NOT EXISTS (
                          SELECT 1 FROM app.price_refresh_result AS result
                           WHERE result.cycle_id=cycle.id
                             AND result.retailer_code = ANY(string_to_array(?, ','))
                             AND result.status<>'SUCCEEDED')))=0
                  AND COALESCE(MAX(cycle.started_at) < now()-interval '2 hours',true)
                FROM app.price_refresh_cycle AS cycle WHERE cycle.market_id=? AND cycle.cycle_date=?
                """, Boolean.class, String.join(",", requiredCodes), market.id(), today));
    }

    private static List<String> requiredCodes(List<ChainPriceSource> chains) {
        return chains.stream().filter(ChainPriceSource::required).map(ChainPriceSource::code).toList();
    }

    static int consecutiveDays(Set<LocalDate> successfulDays, LocalDate today) {
        LocalDate day = successfulDays.contains(today) ? today : today.minusDays(1);
        int count = 0;
        while (successfulDays.contains(day)) { count++; day = day.minusDays(1); }
        return count;
    }

    /** The default market's cycles. */
    public Map<String, Object> status() {
        return status(markets.defaultMarket().code());
    }

    public Map<String, Object> status(String marketCode) {
        Market market = market(marketCode);
        MarketPriceSources marketSources = sourcesOf(market);
        Set<LocalDate> days = new HashSet<>(jdbc.query("""
                SELECT DISTINCT cycle_date FROM app.price_refresh_cycle WHERE status='SUCCEEDED' AND market_id=?
                """, (rs, n) -> rs.getObject(1, LocalDate.class), market.id()));
        return Map.of("consecutiveSuccessfulDays", consecutiveDays(days, market.today()),
                "requiredDays", 7,
                "freshnessRule", marketSources.freshnessRule(),
                "cycles", jdbc.queryForList("""
                        SELECT id,cycle_date::text AS cycle_date,started_at,finished_at,status
                        FROM app.price_refresh_cycle WHERE market_id=? ORDER BY id DESC LIMIT 21
                        """, market.id()),
                "results", jdbc.queryForList("""
                        SELECT cycle_id,retailer_code,snapshot_date::text AS snapshot_date,rows_saved,status,detail,finished_at
                        FROM app.price_refresh_result WHERE cycle_id IN
                        (SELECT id FROM app.price_refresh_cycle WHERE market_id=? ORDER BY id DESC LIMIT 21)
                        ORDER BY cycle_id DESC,retailer_code
                        """, market.id()));
    }

    private Market market(String marketCode) {
        return markets.forCode(marketCode).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Nepoznato tržište: " + marketCode));
    }

    private MarketPriceSources sourcesOf(Market market) {
        MarketPriceSources marketSources = sources.get(market.code());
        if (marketSources == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Tržište " + market.code() + " nema izvore cena.");
        }
        return marketSources;
    }
}
