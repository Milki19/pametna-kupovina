package rs.pametnakupovina.backend.retailerlocation.lidl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;

@RestController
@RequestMapping("/api/v1/imports/retailers/LIDL/locations")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class LidlLocationImportController {

    private final LidlLocationImportService importService;

    public LidlLocationImportController(
            LidlLocationImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping("/latest")
    public RetailerLocationImportResult importLatest() {
        return importService.importLatest();
    }
}
