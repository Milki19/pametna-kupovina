package rs.pametnakupovina.backend.place;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.List;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceSearchController {

    private final PlaceSearchService placeSearchService;

    public PlaceSearchController(PlaceSearchService placeSearchService) {
        this.placeSearchService = placeSearchService;
    }

    /** Polazna tačka iz adrese: web, i Android kad geokoder telefona ne nađe adresu. */
    @GetMapping
    public List<PlaceSearchService.Place> search(
            DeviceCaller caller,
            @RequestParam(name = "query") String query
    ) {
        return placeSearchService.search(caller.accountId(), query);
    }

    /** Adresa trenutne lokacije umesto koordinata; 204 kad mapa tu nema adresu. */
    @GetMapping("/reverse")
    public ResponseEntity<PlaceSearchService.Place> reverse(
            DeviceCaller caller,
            @RequestParam(name = "latitude") double latitude,
            @RequestParam(name = "longitude") double longitude
    ) {
        PlaceSearchService.Place place = placeSearchService.nameOf(caller.accountId(), latitude, longitude);
        return place == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(place);
    }
}
