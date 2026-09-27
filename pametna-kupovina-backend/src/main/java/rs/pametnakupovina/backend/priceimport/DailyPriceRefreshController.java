package rs.pametnakupovina.backend.priceimport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/imports/daily")
@ConditionalOnProperty(name="price-import.http-endpoints.enabled", havingValue="true", matchIfMissing=true)
public class DailyPriceRefreshController {
    private final DailyPriceRefreshService service;
    public DailyPriceRefreshController(DailyPriceRefreshService service) { this.service = service; }
    /** @param market ISO code; the default market when left out */
    @GetMapping public Map<String, Object> status(@RequestParam(name="market", required=false) String market) {
        return market == null ? service.status() : service.status(market);
    }
    /** @param market ISO code; every market with price sources when left out */
    @PostMapping public Map<String, Object> refresh(@RequestParam(name="market", required=false) String market) {
        return market == null ? service.refresh(true) : service.refresh(market, true);
    }
}
