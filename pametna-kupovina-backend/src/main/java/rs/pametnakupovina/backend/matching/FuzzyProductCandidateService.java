package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class FuzzyProductCandidateService {

    private static final int MIN_LIMIT = 3;
    private static final int MAX_LIMIT = 5;

    private final FuzzyProductCandidateRepository candidateRepository;
    private final ProductNameNormalizer productNameNormalizer;

    public FuzzyProductCandidateService(
            FuzzyProductCandidateRepository candidateRepository,
            ProductNameNormalizer productNameNormalizer
    ) {
        this.candidateRepository = candidateRepository;
        this.productNameNormalizer = productNameNormalizer;
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

        return candidateRepository.findByNormalizedName(
                normalizedQuery,
                limit
        );
    }
}
