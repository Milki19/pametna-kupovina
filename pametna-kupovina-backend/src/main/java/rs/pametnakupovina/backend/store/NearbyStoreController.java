package rs.pametnakupovina.backend.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stores")
public class NearbyStoreController {

    private final NearbyStoreService service;

    public NearbyStoreController(NearbyStoreService service) {
        this.service = service;
    }

    @GetMapping("/nearby")
    public List<NearbyStore> findNearby(
            @RequestParam("latitude") double latitude,
            @RequestParam("longitude") double longitude,
            @RequestParam(
                    name = "radiusMeters",
                    defaultValue = "5000"
            ) int radiusMeters,
            @RequestParam(
                    name = "limit",
                    defaultValue = "20"
            ) int limit
    ) {
        return service.findNearby(
                latitude,
                longitude,
                radiusMeters,
                limit
        );
    }
}
