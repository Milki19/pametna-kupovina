package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;

@RestController
@RequestMapping("/api/v1/imports/retailers/UNIVEREXPORT/locations")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class UniverexportLocationImportController {

    private final UniverexportLocationImportService importService;

    public UniverexportLocationImportController(
            UniverexportLocationImportService importService
    ) {
        this.importService = importService;
    }

    @PostMapping("/latest")
    public RetailerLocationImportResult importLatest() {
        return importService.importLatest();
    }
}
