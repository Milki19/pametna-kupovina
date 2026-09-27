package rs.pametnakupovina.backend.retailerlocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.market.MarketSchedule;
import rs.pametnakupovina.backend.retailerlocation.dis.DisLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.europrom.EuropromLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.idearoda.IdeaRodaLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.lidl.LidlLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.maxi.MaxiLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.univerexport.UniverexportLocationImportService;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(
        name = "verified-location-import.schedule.enabled",
        havingValue = "true"
)
public class VerifiedLocationImportScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(
            VerifiedLocationImportScheduler.class
    );

    private final MarketRepository marketRepository;
    private final String cron;
    /** In the order they have always run. */
    private final Map<String, Supplier<RetailerLocationImportResult>> importers;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VerifiedLocationImportScheduler(
            DisLocationImportService disImportService,
            EuropromLocationImportService europromImportService,
            LidlLocationImportService lidlImportService,
            MaxiLocationImportService maxiImportService,
            IdeaRodaLocationImportService ideaRodaImportService,
            UniverexportLocationImportService univerexportImportService,
            MarketRepository marketRepository,
            @Value("${verified-location-import.schedule.cron:0 0 2 * * SUN}")
            String cron
    ) {
        this.marketRepository = marketRepository;
        this.cron = cron;

        Map<String, Supplier<RetailerLocationImportResult>> importers =
                new LinkedHashMap<>();
        importers.put("DIS", disImportService::importLatest);
        importers.put("EUROPROM", europromImportService::importLatest);
        importers.put("LIDL", lidlImportService::importLatest);
        importers.put("MAXI", maxiImportService::importLatest);
        importers.put("IDEA_RODA", ideaRodaImportService::importLatest);
        importers.put("UNIVEREXPORT", univerexportImportService::importLatest);
        this.importers = Collections.unmodifiableMap(importers);
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        MarketSchedule.addCronTasks(
                registrar,
                marketRepository,
                cron,
                List.copyOf(importers.keySet()),
                this::importLocations
        );
    }

    public void importVerifiedLocations() {
        importLocations(List.copyOf(importers.keySet()));
    }

    private void importLocations(List<String> retailerCodes) {
        if (!running.compareAndSet(false, true)) {
            log.warn(
                    "Preskočena sinhronizacija lokacija: prethodna još traje."
            );
            return;
        }

        try {
            retailerCodes.forEach(code -> importRetailer(code, importers.get(code)));
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
