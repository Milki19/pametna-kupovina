package rs.pametnakupovina.backend.storepricing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(
        "/api/v1/imports/retailers/IDEA_RODA/price-formats"
)
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class IdeaRodaPriceFormatMappingController {

    private final IdeaRodaPriceFormatMappingService importService;

    public IdeaRodaPriceFormatMappingController(
            IdeaRodaPriceFormatMappingService importService
    ) {
        this.importService = importService;
    }

    @PostMapping("/latest")
    public StorePriceFormatImportResult importLatest() {
        return importService.importLatest();
    }
}
