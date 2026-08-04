package rs.pametnakupovina.backend.geocoding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stores")
public class StoreGeocodingController {

    private final StoreGeocodingService service;

    public StoreGeocodingController(
            StoreGeocodingService service
    ) {
        this.service = service;
    }

    @PostMapping("/{storeId}/geocoding-results")
    public StoreGeocodingResult recordCandidate(
            @PathVariable("storeId") Long storeId,
            @RequestBody StoreGeocodingCandidateRequest request
    ) {
        return service.recordCandidate(storeId, request);
    }

    @GetMapping("/geocoding-review-queue")
    public List<StoreGeocodingResult> findReviewQueue(
            @RequestParam("city") String city
    ) {
        return service.findReviewQueue(city);
    }

    @PostMapping("/{storeId}/geocoding-review")
    public StoreGeocodingResult review(
            @PathVariable("storeId") Long storeId,
            @RequestBody StoreGeocodingReviewRequest request
    ) {
        return service.review(storeId, request);
    }
}

