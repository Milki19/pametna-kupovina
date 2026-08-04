package rs.pametnakupovina.backend.store;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stores")
public class NearbyStoreController {

    private final NearbyStoreService service;

    private final PreciseLocationPolicy preciseLocationPolicy;

    public NearbyStoreController(
            NearbyStoreService service,
            PreciseLocationPolicy preciseLocationPolicy
    ) {
        this.service = service;
        this.preciseLocationPolicy = preciseLocationPolicy;
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
        return preciseLocationPolicy.useForRequest(
                PreciseLocationPurpose.NEARBY_STORES,
                latitude,
                longitude,
                location -> service.findNearby(
                        location.latitude(),
                        location.longitude(),
                        radiusMeters,
                        limit
                )
        );
    }
}
