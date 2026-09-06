package rs.pametnakupovina.backend.retailerlocation.idearoda;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;

@RestController
@RequestMapping("/api/v1/imports/retailers/IDEA_RODA/locations")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class IdeaRodaLocationImportController {

    private final IdeaRodaLocationImportService importService;

    public IdeaRodaLocationImportController(
            IdeaRodaLocationImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping("/latest")
    public RetailerLocationImportResult importLatest() {
        return importService.importLatest();
    }
}
