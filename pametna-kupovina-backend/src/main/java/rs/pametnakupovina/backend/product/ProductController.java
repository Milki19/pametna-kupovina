package rs.pametnakupovina.backend.product;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
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
import rs.pametnakupovina.backend.privacy.PreciseLocationPolicy;
import rs.pametnakupovina.backend.privacy.PreciseLocationPurpose;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final CanonicalProductSearchService canonicalSearchService;
    private final FuzzyProductCandidateService fuzzyCandidateService;
    private final ProductMatchDecisionService matchDecisionService;
    private final ProductMatchFeedbackService matchFeedbackService;
    private final CanonicalProductDetailsService productDetailsService;
    private final ProductFamilyDetailsService familyDetailsService;
    private final PreciseLocationPolicy locationPolicy;
    private final SaleService saleService;

    public ProductController(
            CanonicalProductSearchService canonicalSearchService,
            FuzzyProductCandidateService fuzzyCandidateService,
            ProductMatchDecisionService matchDecisionService,
            ProductMatchFeedbackService matchFeedbackService,
            CanonicalProductDetailsService productDetailsService,
            ProductFamilyDetailsService familyDetailsService,
            PreciseLocationPolicy locationPolicy,
            SaleService saleService
    ) {
        this.canonicalSearchService = canonicalSearchService;
        this.fuzzyCandidateService = fuzzyCandidateService;
        this.matchDecisionService = matchDecisionService;
        this.matchFeedbackService = matchFeedbackService;
        this.productDetailsService = productDetailsService;
        this.familyDetailsService = familyDetailsService;
        this.locationPolicy = locationPolicy;
        this.saleService = saleService;
    }

    @GetMapping("/families/{productFamilyId}")
    public ProductFamilyDetailsResponse familyDetails(
            @PathVariable("productFamilyId") Long productFamilyId
    ) {
        try {
            return familyDetailsService.find(productFamilyId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Porodica proizvoda nije pronađena: "
                                    + productFamilyId
                    ));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    @GetMapping("/{canonicalProductId}")
    public CanonicalProductDetailsResponse details(
            @PathVariable("canonicalProductId") Long canonicalProductId,
            @RequestParam(
                    name = "date",
                    required = false
            ) java.time.LocalDate date,
            @RequestParam(
                    name = "historyLimit",
                    defaultValue = "30"
            ) int historyLimit
    ) {
        try {
            return productDetailsService.find(
                    canonicalProductId,
                    date,
                    historyLimit
            ).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Canonical proizvod nije pronađen: "
                            + canonicalProductId
            ));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
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
            ) int limit,
            @RequestParam(name = "includeWithoutPrice", defaultValue = "false") boolean includeWithoutPrice,
            @RequestParam(name = "onSale", defaultValue = "false") boolean onSale,
            // Zaokruženo na oko kilometar, samo za redosled; deo ključa
            // deljenog odgovora najviše pet minuta.
            @RequestParam(name = "latitude", required = false) Double latitude,
            @RequestParam(name = "longitude", required = false) Double longitude,
            HttpServletResponse response
    ) {
        try {
            if (latitude != null && longitude != null) {
                return keptForAWhile(response, locationPolicy.useRoughlyForAWhile(
                        PreciseLocationPurpose.SEARCH_RANKING,
                        latitude,
                        longitude,
                        near -> canonicalSearchService.search(query, page, limit, includeWithoutPrice, near, onSale)
                ));
            }
            return keptForAWhile(response,
                    canonicalSearchService.search(query, page, limit, includeWithoutPrice, null, onSale));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * Products on sale today, the largest discount first. With a location,
     * only chains with a shop nearby; the location is rounded to about a
     * kilometre and kept only as part of the shared page's key, five minutes.
     */
    @GetMapping("/on-sale")
    public SalePage onSale(
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "retailer", required = false) String retailer,
            @RequestParam(name = "query", required = false) String query,
            @RequestParam(name = "minDiscount", defaultValue = "1") int minDiscount,
            @RequestParam(name = "sort", defaultValue = "DISCOUNT") SaleSort sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "latitude", required = false) Double latitude,
            @RequestParam(name = "longitude", required = false) Double longitude,
            @RequestParam(name = "radiusMeters", required = false) Integer radiusMeters,
            HttpServletResponse response
    ) {
        try {
            if (latitude != null && longitude != null) {
                return keptForAWhile(response, locationPolicy.useRoughlyForAWhile(
                        PreciseLocationPurpose.NEARBY_SALES,
                        latitude,
                        longitude,
                        near -> saleService.find(near, radiusMeters, category, retailer, query,
                                minDiscount, sort, page, limit)
                ));
            }
            return keptForAWhile(response,
                    saleService.find(null, radiusMeters, category, retailer, query, minDiscount, sort, page, limit));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * Search results and sales are the same for everyone and change with
     * the daily import, so the phone keeps them five minutes: going back to
     * a search or to Akcije asks nothing. The server keeps the same answer
     * five minutes for everyone too ({@link CatalogMemo#rememberShared}).
     * The phone's copy stays private, since the address can carry the
     * rounded location; after five minutes the ETag lets an unchanged answer
     * come back as 304.
     */
    static final String KEPT_FOR_A_WHILE = "private, max-age=300";

    private static <T> T keptForAWhile(HttpServletResponse response, T answer) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, KEPT_FOR_A_WHILE);
        return answer;
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
