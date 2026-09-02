package rs.pametnakupovina.backend.priceimport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.product.ProductCatalogMaintenanceService;
import rs.pametnakupovina.backend.product.ProductCatalogRefreshResult;

import java.util.List;

@RestController
@RequestMapping("/api/v1/imports")
@ConditionalOnProperty(
        name = "price-import.http-endpoints.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class PriceImportController {

    private final PriceImportService priceImportService;
    private final ProductCatalogMaintenanceService catalogMaintenanceService;

    public PriceImportController(
            PriceImportService priceImportService,
            ProductCatalogMaintenanceService catalogMaintenanceService
    ) {
        this.priceImportService = priceImportService;
        this.catalogMaintenanceService = catalogMaintenanceService;
    }

    @PostMapping("/retailers/{retailerCode}")
    public ImportResult importRetailerPrices(
            @PathVariable("retailerCode") String retailerCode
    ) {
        return priceImportService.importPrices(retailerCode);
    }

    @PostMapping("/catalog/rebuild")
    public List<ProductCatalogRefreshResult> rebuildCatalog() {
        return catalogMaintenanceService.refreshAll();
    }
}
