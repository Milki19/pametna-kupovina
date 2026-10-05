package rs.pametnakupovina.backend.place;

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

    /** Polazna tačka iz adrese, za web verziju koja nema geokoder telefona. */
    @GetMapping
    public List<PlaceSearchService.Place> search(
            DeviceCaller caller,
            @RequestParam(name = "query") String query
    ) {
        return placeSearchService.search(caller.accountId(), query);
    }
}
