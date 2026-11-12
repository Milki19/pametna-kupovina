package rs.pametnakupovina.backend.shoppinglist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.MarketRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Builds the plan's price tables again once the prices settle after an
 * import, and after midnight for the new day (V172). Until then the plan
 * works its prices out itself, as before.
 */
@Component
@ConditionalOnProperty(name = "plan-facts.refresh.enabled", havingValue = "true")
public class PlanFactsRefresher {

    private static final Logger log = LoggerFactory.getLogger(PlanFactsRefresher.class);

    private final PlanFacts facts;
    private final MarketRepository markets;
    private final Duration quiet;
    private final Duration gap;
    private final Duration afterFailure;
    private final Clock clock = Clock.systemUTC();
    private Instant lastTry = Instant.EPOCH;
    private boolean lastFailed;

    public PlanFactsRefresher(
            PlanFacts facts,
            MarketRepository markets,
            @Value("${plan-facts.refresh.quiet-seconds:120}") long quietSeconds,
            @Value("${plan-facts.refresh.min-gap-minutes:10}") long gapMinutes,
            @Value("${plan-facts.refresh.after-failure-minutes:60}") long afterFailureMinutes
    ) {
        this.facts = facts;
        this.markets = markets;
        this.quiet = Duration.ofSeconds(quietSeconds);
        this.gap = Duration.ofMinutes(gapMinutes);
        this.afterFailure = Duration.ofMinutes(afterFailureMinutes);
    }

    @Scheduled(
            fixedDelayString = "${plan-facts.refresh.check-ms:60000}",
            initialDelayString = "${plan-facts.refresh.initial-delay-ms:120000}"
    )
    public void refreshIfDue() {
        try {
            LocalDate today = markets.defaultMarket().today();
            if (!facts.enabled() || facts.freshFor(today)) {
                return;
            }
            Instant now = clock.instant();
            // A day's first build waits for nothing but a running import;
            // a build after a change waits a little, so that one import
            // does not start several.
            Duration wait = lastFailed ? afterFailure
                    : today.equals(facts.builtFor()) ? gap : Duration.ZERO;
            if (lastTry.plus(wait).isAfter(now)) {
                return;
            }
            if (facts.importRunning() || facts.changedWithin(quiet)) {
                return;
            }
            lastTry = now;
            lastFailed = true;
            PlanFacts.Build built = facts.build(today);
            lastFailed = false;
            if (built != null) {
                log.info("Plan facts built for {} in {} ms: {} prices, {} products with an old price",
                        built.asOfDate(), built.tookMs(), built.prices(), built.looseProducts());
            }
        } catch (RuntimeException failure) {
            log.error("Plan facts not built, the plan works its prices out itself", failure);
        }
    }
}
