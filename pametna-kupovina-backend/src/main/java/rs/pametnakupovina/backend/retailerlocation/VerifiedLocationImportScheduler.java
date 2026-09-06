package rs.pametnakupovina.backend.retailerlocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.retailerlocation.dis.DisLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.europrom.EuropromLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.idearoda.IdeaRodaLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.lidl.LidlLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.maxi.MaxiLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.univerexport.UniverexportLocationImportService;

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
    private final EuropromLocationImportService europromImportService;
    private final LidlLocationImportService lidlImportService;
    private final MaxiLocationImportService maxiImportService;
    private final IdeaRodaLocationImportService ideaRodaImportService;
    private final UniverexportLocationImportService univerexportImportService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VerifiedLocationImportScheduler(
            DisLocationImportService disImportService,
            EuropromLocationImportService europromImportService,
            LidlLocationImportService lidlImportService,
            MaxiLocationImportService maxiImportService,
            IdeaRodaLocationImportService ideaRodaImportService,
            UniverexportLocationImportService univerexportImportService
    ) {
        this.disImportService = disImportService;
        this.europromImportService = europromImportService;
        this.lidlImportService = lidlImportService;
        this.maxiImportService = maxiImportService;
        this.ideaRodaImportService = ideaRodaImportService;
        this.univerexportImportService = univerexportImportService;
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
            importRetailer(
                    "EUROPROM",
                    europromImportService::importLatest
            );
            importRetailer("LIDL", lidlImportService::importLatest);
            importRetailer("MAXI", maxiImportService::importLatest);
            importRetailer(
                    "IDEA_RODA",
                    ideaRodaImportService::importLatest
            );
            importRetailer(
                    "UNIVEREXPORT",
                    univerexportImportService::importLatest
            );
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
