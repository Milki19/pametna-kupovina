package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductMatchDecisionService {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MIN_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    private static final BigDecimal NO_CANDIDATE_SCORE =
            new BigDecimal("0.0000");

    private static final BigDecimal USER_CONFIRMED_SCORE =
            new BigDecimal("1.0000");

    private static final String USER_CONFIRMATION_VERSION =
            "user-confirmation-v1";

    private static final String USER_REJECTION_VERSION =
            "user-rejection-v1";

    private final FuzzyProductCandidateService candidateService;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductMatchThresholdPolicy thresholdPolicy;
    private final ProductMatchDecisionRepository decisionRepository;
    private final ProductMatchFeedbackRepository feedbackRepository;
    private final ProductMatchClientTokenValidator clientTokenValidator;

    public ProductMatchDecisionService(
            FuzzyProductCandidateService candidateService,
            ProductNameNormalizer productNameNormalizer,
            ProductMatchThresholdPolicy thresholdPolicy,
            ProductMatchDecisionRepository decisionRepository,
            ProductMatchFeedbackRepository feedbackRepository,
            ProductMatchClientTokenValidator clientTokenValidator
    ) {
        this.candidateService = candidateService;
        this.productNameNormalizer = productNameNormalizer;
        this.thresholdPolicy = thresholdPolicy;
        this.decisionRepository = decisionRepository;
        this.feedbackRepository = feedbackRepository;
        this.clientTokenValidator = clientTokenValidator;
    }

    @Transactional
    public ProductMatchDecision decide(
            String query,
            int limit
    ) {
        return decide(query, limit, null);
    }

    @Transactional
    public ProductMatchDecision decide(
            String query,
            int limit,
            String clientToken
    ) {
        validateQueryAndLimit(query, limit);

        String normalizedQuery = productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        String normalizedClientToken =
                clientTokenValidator.validateOptional(clientToken);

        if (normalizedClientToken != null) {
            Optional<ReusableProductMatch> reusableMatch =
                    feedbackRepository.findReusableFeedback(
                            normalizedClientToken,
                            normalizedQuery
                    );

            if (reusableMatch.isPresent()) {
                return reusedDecision(reusableMatch.orElseThrow());
            }
        }

        List<FuzzyProductCandidate> candidates =
                candidateService.findCandidates(query, limit);

        Optional<FuzzyProductCandidate> topCandidate =
                candidates.stream().findFirst();

        Optional<BigDecimal> topScore = topCandidate
                .map(candidate -> candidate.score().totalScore());

        ProductMatchStatus status = thresholdPolicy.classify(topScore);

        Long topCandidateId = topCandidate
                .map(FuzzyProductCandidate::canonicalProductId)
                .orElse(null);

        Long matchedCanonicalProductId =
                status == ProductMatchStatus.AUTO_ACCEPTED
                        ? topCandidateId
                        : null;

        BigDecimal score = topScore.orElse(NO_CANDIDATE_SCORE);
        String algorithmVersion = thresholdPolicy.algorithmVersion();

        Long decisionId = decisionRepository.save(
                query,
                normalizedQuery,
                topCandidateId,
                matchedCanonicalProductId,
                score,
                status,
                algorithmVersion,
                normalizedClientToken
        );

        return new ProductMatchDecision(
                decisionId,
                status,
                matchedCanonicalProductId,
                score,
                thresholdPolicy.autoAcceptThreshold(),
                thresholdPolicy.confirmationThreshold(),
                algorithmVersion,
                candidates,
                ProductMatchDecisionSource.ALGORITHM,
                null
        );
    }

    private void validateQueryAndLimit(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query ne sme biti prazan"
            );
        }

        if (query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "Parametar query ne sme biti duži od 500 znakova"
            );
        }

        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Limit za matching kandidate mora biti između 3 i 5"
            );
        }
    }

    private ProductMatchDecision reusedDecision(
            ReusableProductMatch reusableMatch
    ) {
        if (reusableMatch.action()
                == ProductMatchFeedbackAction.REJECTED) {
            return new ProductMatchDecision(
                    reusableMatch.decisionId(),
                    ProductMatchStatus.UNMATCHED,
                    null,
                    NO_CANDIDATE_SCORE,
                    thresholdPolicy.autoAcceptThreshold(),
                    thresholdPolicy.confirmationThreshold(),
                    USER_REJECTION_VERSION,
                    List.of(),
                    ProductMatchDecisionSource.USER_REJECTION,
                    reusableMatch.feedbackId()
            );
        }

        return new ProductMatchDecision(
                reusableMatch.decisionId(),
                ProductMatchStatus.AUTO_ACCEPTED,
                reusableMatch.canonicalProductId(),
                USER_CONFIRMED_SCORE,
                thresholdPolicy.autoAcceptThreshold(),
                thresholdPolicy.confirmationThreshold(),
                USER_CONFIRMATION_VERSION,
                List.of(),
                ProductMatchDecisionSource.USER_CONFIRMATION,
                reusableMatch.feedbackId()
        );
    }
}
