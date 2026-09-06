package rs.pametnakupovina.backend.priceimport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/imports/catalog")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class GovernmentDatasetCatalogController {

    private final GovernmentDatasetCatalogService service;

    public GovernmentDatasetCatalogController(
            GovernmentDatasetCatalogService service
    ) {
        this.service = service;
    }

    @GetMapping
    public List<GovernmentDatasetCandidate> listCandidates() {
        return service.findAll();
    }

    @PostMapping("/discover")
    public GovernmentDatasetCatalogSyncResult discover() {
        return service.synchronize();
    }
}
