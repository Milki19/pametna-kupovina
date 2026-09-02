package rs.pametnakupovina.backend.priceimport.maxi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "maxi.price-import.schedule.enabled",
        havingValue = "true"
)
public class MaxiPriceImportScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(MaxiPriceImportScheduler.class);

    private final MaxiPriceImportCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MaxiPriceImportScheduler(
            MaxiPriceImportCoordinator coordinator
    ) {
        this.coordinator = coordinator;
    }

    @Scheduled(
            cron = "${maxi.price-import.schedule.cron:0 0 4 * * *}",
            zone = "${maxi.price-import.zone:Europe/Belgrade}"
    )
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
