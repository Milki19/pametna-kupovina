package rs.pametnakupovina.backend.retailerlocation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;

import java.util.List;

@RestController
@RequestMapping("/api/v1/retailer-locations")
public class RetailerLocationController {

    private final RetailerLocationService service;

    private final PreciseLocationPolicy preciseLocationPolicy;

    public RetailerLocationController(
            RetailerLocationService service,
            PreciseLocationPolicy preciseLocationPolicy
    ) {
        this.service = service;
        this.preciseLocationPolicy = preciseLocationPolicy;
    }

    @GetMapping("/nearest")
    public List<RetailerLocationResponse> findNearest(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "limit",
                    defaultValue = "10"
            ) int limit
    ) {
        return preciseLocationPolicy.useForRequest(
                PreciseLocationPurpose.NEAREST_RETAILER_LOCATIONS,
                latitude,
                longitude,
                location -> service.findNearest(
                        location.latitude(),
                        location.longitude(),
                        limit
                )
        );
    }
}
