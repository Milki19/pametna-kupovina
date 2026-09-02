package rs.pametnakupovina.backend.priceimport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(
        name = "price-import.schedule.enabled",
        havingValue = "true"
)
public class PriceImportScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PriceImportScheduler.class);

    private final PriceImportService priceImportService;
    private final List<String> retailerCodes;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PriceImportScheduler(
            PriceImportService priceImportService,
            @Value("${price-import.schedule.retailers:EUROPROM}")
            String configuredRetailerCodes
    ) {
        this.priceImportService = priceImportService;
        this.retailerCodes = Arrays.stream(
                        configuredRetailerCodes.split(",")
                )
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .distinct()
                .toList();
    }

    @Scheduled(
            cron = "${price-import.schedule.cron:0 0 3 * * *}",
            zone = "${price-import.schedule.zone:Europe/Belgrade}"
    )
    public void importConfiguredRetailers() {
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
