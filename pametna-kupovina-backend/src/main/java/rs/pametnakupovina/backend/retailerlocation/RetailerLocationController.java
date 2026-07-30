package rs.pametnakupovina.backend.retailerlocation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/retailer-locations")
public class RetailerLocationController {

    private final RetailerLocationService service;

    public RetailerLocationController(
            RetailerLocationService service
    ) {
        this.service = service;
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
        return service.findNearest(
                latitude,
                longitude,
                limit
        );
    }
}