package rs.pametnakupovina.backend.priceimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.MarketSchedule;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "price-import.schedule.enabled",
        havingValue = "true"
)
public class PriceImportScheduler implements SchedulingConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(PriceImportScheduler.class);

    private final PriceImportService priceImportService;
    private final MarketRepository marketRepository;
    private final List<String> retailerCodes;
    private final String cron;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PriceImportScheduler(
            PriceImportService priceImportService,
            MarketRepository marketRepository,
            @Value("${price-import.schedule.retailers:EUROPROM}")
            String configuredRetailerCodes,
            @Value("${price-import.schedule.cron:0 0 3 * * *}")
            String cron
    ) {
        this.priceImportService = priceImportService;
        this.marketRepository = marketRepository;
        this.cron = cron;
        this.retailerCodes = Arrays.stream(
                        configuredRetailerCodes.split(",")
                )
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        MarketSchedule.addCronTasks(
                registrar,
                marketRepository,
                cron,
                retailerCodes,
                this::importRetailers
        );
    }

    public void importConfiguredRetailers() {
        importRetailers(retailerCodes);
    }

    private void importRetailers(List<String> retailerCodes) {
        if (!running.compareAndSet(false, true)) {
            log.warn("Preskočen raspored: prethodni import još traje.");
            return;
        }

        try {
            for (String retailerCode : retailerCodes) {
                try {
                    ImportResult result = priceImportService.importPrices(
                            retailerCode
                    );

                    log.info(
                            "Automatski import završen: retailer={}, "
                                    + "status={}, snapshotDate={}, rowsSaved={}",
                            retailerCode,
                            result.status(),
                            result.snapshotDate(),
                            result.rowsSaved()
                    );
                } catch (RuntimeException exception) {
                    log.error(
                            "Automatski import nije uspeo za retailer={}",
                            retailerCode,
                            exception
                    );
                }
            }
        } finally {
            running.set(false);
        }
    }
}
