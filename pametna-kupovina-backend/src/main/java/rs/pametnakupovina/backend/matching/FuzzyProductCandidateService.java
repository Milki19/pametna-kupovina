package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class FuzzyProductCandidateService {

    private static final int MIN_LIMIT = 3;
    private static final int MAX_LIMIT = 5;
    private static final int CANDIDATE_POOL_LIMIT = 25;

    private final FuzzyProductCandidateRepository candidateRepository;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ProductMatchScorer productMatchScorer;

    public FuzzyProductCandidateService(
            FuzzyProductCandidateRepository candidateRepository,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ProductMatchScorer productMatchScorer
    ) {
        this.candidateRepository = candidateRepository;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.productMatchScorer = productMatchScorer;
    }

    public List<FuzzyProductCandidate> findCandidates(
            String query,
            int limit
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query ne sme biti prazan"
            );
        }

        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Limit za matching kandidate mora biti između 3 i 5"
            );
        }

        String normalizedQuery = productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        Optional<ParsedQuantity> queryQuantity =
                productQuantityParser.parse(query);

        return candidateRepository.findByNormalizedName(
                        normalizedQuery,
                        CANDIDATE_POOL_LIMIT
                ).stream()
                .map(candidate -> toScoredCandidate(
                        normalizedQuery,
                        queryQuantity,
                        candidate
                ))
                .sorted(
                        Comparator.comparing(
                                        (FuzzyProductCandidate candidate) ->
                                                candidate.score().totalScore()
                                )
                                .reversed()
                                .thenComparing(
                                        FuzzyProductCandidate::nameSimilarity,
                                        Comparator.reverseOrder()
                                )
                                .thenComparing(
                                        FuzzyProductCandidate::name
                                )
                                .thenComparing(
                                        FuzzyProductCandidate::canonicalProductId
                                )
                )
                .limit(limit)
                .toList();
    }

    private FuzzyProductCandidate toScoredCandidate(
            String normalizedQuery,
            Optional<ParsedQuantity> queryQuantity,
            FuzzyProductCandidateRow candidate
    ) {
        ProductMatchScore score = productMatchScorer.score(
                normalizedQuery,
                queryQuantity,
                candidate.nameSimilarity(),
                candidate.brand(),
                candidate.quantityValue(),
                candidate.baseUnit()
        );

        return new FuzzyProductCandidate(
                candidate.canonicalProductId(),
                candidate.name(),
                candidate.brand(),
                candidate.barcode(),
                candidate.quantityValue(),
                candidate.baseUnit(),
                candidate.nameSimilarity(),
                score
        );
    }
}
