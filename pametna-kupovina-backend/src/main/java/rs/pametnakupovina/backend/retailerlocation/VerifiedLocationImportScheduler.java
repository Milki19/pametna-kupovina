package rs.pametnakupovina.backend.retailerlocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.retailerlocation.dis.DisLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.lidl.LidlLocationImportService;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(
        name = "verified-location-import.schedule.enabled",
        havingValue = "true"
)
public class VerifiedLocationImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            VerifiedLocationImportScheduler.class
    );

    private final DisLocationImportService disImportService;
    private final LidlLocationImportService lidlImportService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VerifiedLocationImportScheduler(
            DisLocationImportService disImportService,
            LidlLocationImportService lidlImportService
    ) {
        this.disImportService = disImportService;
        this.lidlImportService = lidlImportService;
    }

    @Scheduled(
            cron = "${verified-location-import.schedule.cron:0 0 2 * * SUN}",
            zone = "${verified-location-import.schedule.zone:Europe/Belgrade}"
    )
    public void importVerifiedLocations() {
        if (!running.compareAndSet(false, true)) {
            log.warn(
                    "Preskočena sinhronizacija lokacija: prethodna još traje."
            );
            return;
        }

        try {
            importRetailer("DIS", disImportService::importLatest);
            importRetailer("LIDL", lidlImportService::importLatest);
        } finally {
            running.set(false);
        }
    }

    private void importRetailer(
            String retailerCode,
            Supplier<RetailerLocationImportResult> importer
    ) {
        try {
            RetailerLocationImportResult result = importer.get();
            log.info(
                    "Automatska sinhronizacija lokacija završena: "
                            + "retailer={}, status={}, rowsSaved={}",
                    retailerCode,
                    result.status(),
                    result.rowsSaved()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "Automatska sinhronizacija lokacija nije uspela za "
                            + "retailer={}",
                    retailerCode,
                    exception
            );
        }
    }
}
