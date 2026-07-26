package rs.pametnakupovina.backend.priceimport;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/imports")
public class PriceImportController {

    private final PriceImportService priceImportService;

    public PriceImportController(
            PriceImportService priceImportService
    ) {
        this.priceImportService = priceImportService;
    }

    @PostMapping("/retailers/{retailerCode}")
    public ImportResult importRetailerPrices(
            @PathVariable("retailerCode") String retailerCode,
            @RequestParam(
                    name = "maxRows",
                    defaultValue = "1000"
            ) int maxRows
    ) {
        if (maxRows < 1 || maxRows > 10_000) {
            throw new IllegalArgumentException(
                    "maxRows mora biti između 1 i 10000"
            );
        }

        return priceImportService.importPrices(
                retailerCode,
                maxRows
        );
    }
}