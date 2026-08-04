package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class ProductMatchDecisionService {

    private static final int MAX_QUERY_LENGTH = 500;

    private static final BigDecimal NO_CANDIDATE_SCORE =
            new BigDecimal("0.0000");

    private final FuzzyProductCandidateService candidateService;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductMatchThresholdPolicy thresholdPolicy;
    private final ProductMatchDecisionRepository decisionRepository;

    public ProductMatchDecisionService(
            FuzzyProductCandidateService candidateService,
            ProductNameNormalizer productNameNormalizer,
            ProductMatchThresholdPolicy thresholdPolicy,
            ProductMatchDecisionRepository decisionRepository
    ) {
        this.candidateService = candidateService;
        this.productNameNormalizer = productNameNormalizer;
        this.thresholdPolicy = thresholdPolicy;
        this.decisionRepository = decisionRepository;
    }

    @Transactional
    public ProductMatchDecision decide(
            String query,
            int limit
    ) {
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "Parametar query ne sme biti duži od 500 znakova"
            );
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
                productNameNormalizer.normalize(query),
                topCandidateId,
                matchedCanonicalProductId,
                score,
                status,
                algorithmVersion
        );

        return new ProductMatchDecision(
                decisionId,
                status,
                matchedCanonicalProductId,
                score,
                thresholdPolicy.autoAcceptThreshold(),
                thresholdPolicy.confirmationThreshold(),
                algorithmVersion,
                candidates
        );
    }
}
