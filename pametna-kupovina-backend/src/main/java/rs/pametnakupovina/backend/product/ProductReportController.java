package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.Optional;

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
            Optional<DeviceCaller> caller,
            @RequestBody ProductReportRequest request
    ) {
        return service.report(
                canonicalProductId,
                caller.map(DeviceCaller::clientTokenHash).orElse(null),
                request
        );
    }
}
