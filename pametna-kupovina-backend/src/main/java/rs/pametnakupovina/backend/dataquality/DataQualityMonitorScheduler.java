package rs.pametnakupovina.backend.dataquality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        name = "data-quality.monitor.enabled",
        havingValue = "true"
)
public class DataQualityMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(
            DataQualityMonitorScheduler.class
    );

    private final DataQualityService service;

    public DataQualityMonitorScheduler(DataQualityService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${data-quality.monitor.interval-ms:3600000}",
            initialDelayString = "${data-quality.monitor.initial-delay-ms:60000}"
    )
    public void check() {
        DataQualityReport report = service.report();

        if ("CRITICAL".equals(report.status())) {
            log.error("M1 data-quality alarm: {}", report);
        } else if ("WARNING".equals(report.status())) {
            log.warn("M1 data-quality upozorenje: {}", report);
        } else {
            log.info(
                    "M1 data-quality provera je zdrava: generatedAt={}",
                    report.generatedAt()
            );
        }
    }
}
