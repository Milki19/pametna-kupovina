package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductReportController {

    private final ProductReportService service;

    public ProductReportController(ProductReportService service) {
        this.service = service;
    }

    @PostMapping("/{canonicalProductId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductReportResponse report(
            @PathVariable("canonicalProductId") Long canonicalProductId,
            @RequestHeader(name = "X-Client-Token", required = false) String clientToken,
            @RequestBody ProductReportRequest request
    ) {
        return service.report(canonicalProductId, clientToken, request);
    }
}
