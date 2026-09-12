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
    @GetMapping public Map<String, Object> status() { return service.status(); }
    @PostMapping public Map<String, Object> refresh() { return service.refresh(true); }
}
