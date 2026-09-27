package rs.pametnakupovina.backend.priceimport.maxi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.MarketSchedule;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "maxi.price-import.schedule.enabled",
        havingValue = "true"
)
public class MaxiPriceImportScheduler implements SchedulingConfigurer {

    private static final Logger log =
            LoggerFactory.getLogger(MaxiPriceImportScheduler.class);

    private final MaxiPriceImportCoordinator coordinator;
    private final MarketRepository marketRepository;
    private final String cron;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MaxiPriceImportScheduler(
            MaxiPriceImportCoordinator coordinator,
            MarketRepository marketRepository,
            @Value("${maxi.price-import.schedule.cron:0 0 4 * * *}")
            String cron
    ) {
        this.coordinator = coordinator;
        this.marketRepository = marketRepository;
        this.cron = cron;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        MarketSchedule.addCronTasks(
                registrar,
                marketRepository,
                cron,
                List.of(MaxiPriceImportCoordinator.RETAILER_CODE),
                codes -> importLatest()
        );
    }

    public void importLatest() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Maxi import je preskočen jer prethodni još traje.");
            return;
        }

        try {
            MaxiLatestImportResult result = coordinator.importLatest();
            log.info(
                    "Automatski Maxi import završen: datum={}, "
                            + "status={}, uspešno={}/{}",
                    result.snapshotDate(),
                    result.status(),
                    result.storesImported(),
                    result.filesFound()
            );
        } catch (RuntimeException exception) {
            log.error("Automatski Maxi import nije uspeo.", exception);
        } finally {
            running.set(false);
        }
    }
}
