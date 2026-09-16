package rs.pametnakupovina.backend.dataquality;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** What the owner decides about products: look-alikes, shoppers' reports and types. */
@Validated
@RestController
@RequestMapping("/api/v1/imports/quality")
public class ProductReviewController {

    private final ProductReviewService service;

    public ProductReviewController(ProductReviewService service) {
        this.service = service;
    }

    @GetMapping("/duplicates")
    public List<ProductMergeSuggestionReview> reviewDuplicates(
            @RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit
    ) {
        return service.reviewMergeSuggestions(limit);
    }

    @PostMapping("/duplicates/{suggestionId}")
    public ProductMergeDecisionResult decideDuplicate(
            @PathVariable long suggestionId,
            @RequestBody ProductMergeDecisionRequest request
    ) {
        return service.decideMerge(suggestionId, request);
    }

    @GetMapping("/product-types/assigned")
    public List<ProductTypeAssignmentReview> reviewTypeAssignments(
            @RequestParam String typeCode,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return service.reviewTypeAssignments(typeCode, query, limit);
    }

    @PostMapping("/product-types/{retailerProductId}/rejection")
    public ProductTypeRejectionResult rejectTypeAssignment(
            @PathVariable long retailerProductId,
            @RequestBody ProductTypeRejectionRequest request
    ) {
        return service.rejectTypeAssignment(retailerProductId, request);
    }

    @GetMapping("/reports")
    public List<ProductReportReview> reviewReports(
            @RequestParam(defaultValue = "NEW") String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit
    ) {
        return service.reviewReports(status, limit);
    }

    @PostMapping("/reports/{reportId}")
    public ProductReportReview decideReport(
            @PathVariable long reportId,
            @RequestBody ProductReportReviewRequest request
    ) {
        return service.decideReport(reportId, request);
    }
}
