package rs.pametnakupovina.backend.priceimport.maxi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/imports/maxi")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class MaxiPriceImportController {

    private final MaxiPriceImportCoordinator coordinator;

    public MaxiPriceImportController(
            MaxiPriceImportCoordinator coordinator
    ) {
        this.coordinator = coordinator;
    }

    @PostMapping("/latest")
    public MaxiLatestImportResult importLatest() {
        return coordinator.importLatest();
    }
}
