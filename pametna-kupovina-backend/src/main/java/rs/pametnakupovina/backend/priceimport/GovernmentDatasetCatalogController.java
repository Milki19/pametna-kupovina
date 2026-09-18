package rs.pametnakupovina.backend.priceimport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.priceimport.probe.ChainRegistrationService;
import rs.pametnakupovina.backend.priceimport.probe.PriceListProbeCoordinator;
import rs.pametnakupovina.backend.priceimport.probe.PriceListProbeReport;
import rs.pametnakupovina.backend.priceimport.probe.RegisteredChain;

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
    private final PriceListProbeCoordinator probeCoordinator;
    private final ChainRegistrationService registrationService;

    public GovernmentDatasetCatalogController(
            GovernmentDatasetCatalogService service,
            PriceListProbeCoordinator probeCoordinator,
            ChainRegistrationService registrationService
    ) {
        this.service = service;
        this.probeCoordinator = probeCoordinator;
        this.registrationService = registrationService;
    }

    @GetMapping
    public List<GovernmentDatasetCandidate> listCandidates() {
        return service.findAll();
    }

    @PostMapping("/discover")
    public GovernmentDatasetCatalogSyncResult discover() {
        return service.synchronize();
    }

    /** Registers a probed chain so the daily cycle starts importing it. */
    @PostMapping("/{candidateId}/register")
    public RegisteredChain register(
            @PathVariable("candidateId") long candidateId,
            @RequestParam(name = "allowReview", defaultValue = "false")
            boolean allowReview
    ) {
        return registrationService.register(candidateId, allowReview);
    }

    /** Reads one chain's published file without saving a single price. */
    @PostMapping("/{candidateId}/probe")
    public PriceListProbeReport probe(
            @PathVariable("candidateId") long candidateId
    ) {
        return probeCoordinator.probe(candidateId);
    }
}
