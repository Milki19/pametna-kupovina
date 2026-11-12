package rs.pametnakupovina.backend.shoppinglist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The prices a plan reads, worked out once after each import instead of on
 * every plan (V172): whether a product's old price can still count, and
 * every price it has in the newest lists. Both depend only on the product
 * and the day.
 *
 * <p>The plan reads them only while they are fresh: built for the day it
 * asks about, with no price changed since. Otherwise, or when anything here
 * fails, it works them out as before, with the same query.
 */
@Component
public class PlanFacts {

    private static final Logger log = LoggerFactory.getLogger(PlanFacts.class);

    private final JdbcClient jdbcClient;
    private final TransactionTemplate repeatableRead;
    private final boolean enabled;

    public PlanFacts(
            JdbcClient jdbcClient,
            PlatformTransactionManager transactionManager,
            @Value("${plan-facts.enabled:true}") boolean enabled
    ) {
        this.jdbcClient = jdbcClient;
        this.repeatableRead = new TransactionTemplate(transactionManager);
        // Every statement of the build reads the one state of the database
        // that it records (app.refresh_plan_facts).
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.enabled = enabled;
    }

    /** Off ({@code plan-facts.enabled=false}), every plan works its prices out itself. */
    public boolean enabled() {
        return enabled;
    }

    /** Whether a plan for this day may read the tables. */
    public boolean freshFor(LocalDate asOfDate) {
        if (!enabled || asOfDate == null) {
            return false;
        }
        return asOfDate.equals(freshDate());
    }

    /** The day the tables are for, while no price changed since; null otherwise. */
    LocalDate freshDate() {
        try {
            List<LocalDate> days = jdbcClient.sql("SELECT app.fresh_plan_facts_date()")
                    .query((resultSet, rowNumber) -> resultSet.getObject(1, LocalDate.class))
                    .list();
            return days.isEmpty() ? null : days.getFirst();
        } catch (DataAccessException failure) {
            log.warn("Plan facts unreadable, the plan works its prices out itself: {}", failure.getMessage());
            return null;
        }
    }

    /** The day the tables were last built for, fresh or not. */
    LocalDate builtFor() {
        List<LocalDate> days = jdbcClient.sql("SELECT as_of_date FROM app.plan_fact_build")
                .query((resultSet, rowNumber) -> resultSet.getObject(1, LocalDate.class))
                .list();
        return days.isEmpty() ? null : days.getFirst();
    }

    public record Build(LocalDate asOfDate, int tookMs, int looseProducts, int prices) {
    }

    /**
     * Builds the tables for this day. Null when another build is running.
     * A failure leaves the old tables, and the plan does not read them.
     */
    public Build build(LocalDate asOfDate) {
        Objects.requireNonNull(asOfDate);
        return repeatableRead.execute(status -> {
            boolean alone = Boolean.TRUE.equals(jdbcClient.sql(
                            "SELECT pg_try_advisory_xact_lock(hashtext('app.refresh_plan_facts'))")
                    .query(Boolean.class)
                    .single());
            if (!alone) {
                return null;
            }
            return jdbcClient.sql("""
                            SELECT as_of_date, took_ms, loose_products, prices
                            FROM app.refresh_plan_facts(:asOfDate)
                            """)
                    .param("asOfDate", asOfDate)
                    .query((resultSet, rowNumber) -> new Build(
                            resultSet.getObject("as_of_date", LocalDate.class),
                            resultSet.getInt("took_ms"),
                            resultSet.getInt("loose_products"),
                            resultSet.getInt("prices")))
                    .single();
        });
    }

    /** A price import is running: it will change the prices again soon. */
    boolean importRunning() {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.import_run AS run
                            WHERE run.status = 'RUNNING'
                              AND run.started_at > NOW() - INTERVAL '6 hours'
                        )
                        """)
                .query(Boolean.class)
                .single());
    }

    /** Some price changed in the last {@code quiet}, maybe in the middle of a longer change. */
    boolean changedWithin(Duration quiet) {
        return Boolean.TRUE.equals(jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM app.plan_fact_change AS change
                            WHERE change.changed_at > clock_timestamp() - make_interval(secs => :seconds)
                        )
                        """)
                .param("seconds", (double) quiet.toSeconds())
                .query(Boolean.class)
                .single());
    }
}
