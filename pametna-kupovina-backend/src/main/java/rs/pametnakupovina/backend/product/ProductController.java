package rs.pametnakupovina.backend.product;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
import rs.pametnakupovina.backend.matching.ProductMatchFeedback;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackRequest;
import rs.pametnakupovina.backend.matching.ProductMatchFeedbackService;
import rs.pametnakupovina.backend.matching.ProductMatchRequest;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CanonicalProductSearchService canonicalSearchService;
    private final ProductSearchService productSearchService;
    private final FuzzyProductCandidateService fuzzyCandidateService;
    private final ProductMatchDecisionService matchDecisionService;
    private final ProductMatchFeedbackService matchFeedbackService;

    public ProductController(
            CanonicalProductSearchService canonicalSearchService,
            ProductSearchService productSearchService,
            FuzzyProductCandidateService fuzzyCandidateService,
            ProductMatchDecisionService matchDecisionService,
            ProductMatchFeedbackService matchFeedbackService
    ) {
        this.canonicalSearchService = canonicalSearchService;
        this.productSearchService = productSearchService;
        this.fuzzyCandidateService = fuzzyCandidateService;
        this.matchDecisionService = matchDecisionService;
        this.matchFeedbackService = matchFeedbackService;
    }

    @GetMapping("/search")
    public CanonicalProductSearchPage search(
            @RequestParam("query") String query,
            @RequestParam(
                    name = "page",
                    defaultValue = "0"
            ) int page,
            @RequestParam(
                    name = "limit",
                    defaultValue = "20"
            ) int limit
    ) {
        try {
            return canonicalSearchService.search(query, page, limit);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    @GetMapping("/offers/search")
    public List<ProductSearchResult> searchOffers(
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
                    request.resolvedLimit(),
                    request.clientToken()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    @PostMapping("/match-decisions/{decisionId}/feedback")
    public ProductMatchFeedback recordMatchFeedback(
            @PathVariable("decisionId") Long decisionId,
            @RequestBody ProductMatchFeedbackRequest request
    ) {
        try {
            return matchFeedbackService.record(decisionId, request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }
}
