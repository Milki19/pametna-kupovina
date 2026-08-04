package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidate;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidateService;
import rs.pametnakupovina.backend.matching.ProductMatchDecision;
import rs.pametnakupovina.backend.matching.ProductMatchDecisionService;
import rs.pametnakupovina.backend.matching.ProductMatchRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductSearchService productSearchService;
    private final FuzzyProductCandidateService fuzzyCandidateService;
    private final ProductMatchDecisionService matchDecisionService;

    public ProductController(
            ProductSearchService productSearchService,
            FuzzyProductCandidateService fuzzyCandidateService,
            ProductMatchDecisionService matchDecisionService
    ) {
        this.productSearchService = productSearchService;
        this.fuzzyCandidateService = fuzzyCandidateService;
        this.matchDecisionService = matchDecisionService;
    }

    @GetMapping("/search")
    public List<ProductSearchResult> search(
            @RequestParam("query") String query,
            @RequestParam(
                    name = "limit",
                    defaultValue = "20"
            ) int limit
    ) {
        try {
            return productSearchService.search(query, limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    @GetMapping("/match-candidates")
    public List<FuzzyProductCandidate> matchCandidates(
            @RequestParam("query") String query,
            @RequestParam(
                    name = "limit",
                    defaultValue = "5"
            ) int limit
    ) {
        try {
            return fuzzyCandidateService.findCandidates(query, limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    @PostMapping("/match-decisions")
    public ProductMatchDecision decideMatch(
            @RequestBody ProductMatchRequest request
    ) {
        try {
            return matchDecisionService.decide(
                    request.query(),
                    request.resolvedLimit()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }
}
