package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ParsedQuantity;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class CanonicalProductSearchService {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_LIMIT = 100;
    private static final BigDecimal EXACT_EAN_SCORE =
            new BigDecimal("1.0000");

    private final CanonicalProductSearchRepository searchRepository;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ProductMatchScorer productMatchScorer;
    private final EanValidator eanValidator;

    public CanonicalProductSearchService(
            CanonicalProductSearchRepository searchRepository,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ProductMatchScorer productMatchScorer,
            EanValidator eanValidator
    ) {
        this.searchRepository = searchRepository;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.productMatchScorer = productMatchScorer;
        this.eanValidator = eanValidator;
    }

    public CanonicalProductSearchPage search(
            String query,
            int page,
            int limit
    ) {
        validate(query, page, limit);

        String strippedQuery = query.strip();
        String normalizedQuery = productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        Optional<ParsedQuantity> queryQuantity =
                productQuantityParser.parse(query);

        String validEan = eanValidator.normalize(strippedQuery)
                .orElse(null);

        List<ScoredRow> scoredRows = searchRepository.findCandidates(
                        normalizedQuery,
                        validEan
                ).stream()
                .map(row -> score(
                        normalizedQuery,
                        queryQuantity,
                        row
                ))
                .sorted(
                        Comparator.comparing(
                                        ScoredRow::score,
                                        Comparator.reverseOrder()
                                )
                                .thenComparing(
                                        row -> row.source()
                                                .nameSimilarity(),
                                        Comparator.reverseOrder()
                                )
                                .thenComparing(row -> row.source().name())
                                .thenComparing(row -> row.source()
                                        .canonicalProductId())
                )
                .toList();

        int totalElements = scoredRows.size();
        long offset = (long) page * limit;
        int fromIndex = (int) Math.min(offset, totalElements);
        int toIndex = (int) Math.min(offset + limit, totalElements);

        List<CanonicalProductSearchItem> items = scoredRows
                .subList(fromIndex, toIndex)
                .stream()
                .map(this::toItem)
                .toList();

        int totalPages = totalElements == 0
                ? 0
                : (int) (
                        ((long) totalElements + limit - 1) / limit
                );

        return new CanonicalProductSearchPage(
                strippedQuery,
                page,
                limit,
                totalElements,
                totalPages,
                (long) page + 1 < totalPages,
                items
        );
    }

    private void validate(String query, int page, int limit) {
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

        if (page < 0) {
            throw new IllegalArgumentException(
                    "Broj stranice ne sme biti negativan"
            );
        }

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Limit mora biti između 1 i 100"
            );
        }
    }

    private ScoredRow score(
            String normalizedQuery,
            Optional<ParsedQuantity> queryQuantity,
            CanonicalProductSearchRow row
    ) {
        BigDecimal score = row.exactEanMatch()
                ? EXACT_EAN_SCORE
                : productMatchScorer.score(
                        normalizedQuery,
                        queryQuantity,
                        row.nameSimilarity(),
                        row.brand(),
                        row.quantityValue(),
                        row.baseUnit()
                ).totalScore();

        return new ScoredRow(row, score);
    }

    private CanonicalProductSearchItem toItem(ScoredRow scoredRow) {
        CanonicalProductSearchRow row = scoredRow.source();

        return new CanonicalProductSearchItem(
                row.canonicalProductId(),
                row.name(),
                row.brand(),
                row.barcode(),
                row.quantityValue(),
                row.baseUnit(),
                scoredRow.score()
        );
    }

    private record ScoredRow(
            CanonicalProductSearchRow source,
            BigDecimal score
    ) {
    }
}
