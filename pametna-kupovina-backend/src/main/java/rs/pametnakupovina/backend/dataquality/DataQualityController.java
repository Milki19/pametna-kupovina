package rs.pametnakupovina.backend.dataquality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/imports/quality")
public class DataQualityController {

    private final DataQualityService service;

    public DataQualityController(DataQualityService service) {
        this.service = service;
    }

    @GetMapping
    public DataQualityReport report() {
        return service.report();
    }

    @GetMapping("/locations")
    public List<StorePricingReview> reviewLocations(
            @RequestParam(defaultValue = "true")
            boolean onlyIneligible,
            @RequestParam(defaultValue = "100")
            @Min(1)
            @Max(500)
            int limit
    ) {
        return service.reviewLocations(onlyIneligible, limit);
    }

    @GetMapping("/product-types")
    public List<ProductTypeCandidateReview> reviewProductTypes(
            @RequestParam(defaultValue = "100")
            @Min(1)
            @Max(500)
            int limit
    ) {
        return service.reviewProductTypes(limit);
    }

    @PostMapping("/product-types/{retailerProductId}")
    public ProductTypeReviewResult reviewProductType(
            @PathVariable long retailerProductId,
            @RequestBody ProductTypeReviewRequest request
    ) {
        return service.reviewProductType(retailerProductId, request);
    }
}
