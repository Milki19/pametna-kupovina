package rs.pametnakupovina.backend.priceimport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="price-refresh.daily.enabled", havingValue="true")
public class DailyPriceRefreshScheduler {
    private final DailyPriceRefreshService service;
    public DailyPriceRefreshScheduler(DailyPriceRefreshService service) { this.service = service; }
    // Catch up after Mac sleep/startup. At most three attempts/day, separated by two hours.
    @Scheduled(fixedDelayString="${price-refresh.daily.poll-ms:900000}", initialDelayString="${price-refresh.daily.initial-delay-ms:60000}")
    public void refresh() { service.refresh(false); }
}
