package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ParsedQuantity;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.shoppinglist.ShoppingIntentResolver;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CanonicalProductSearchService {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_LIMIT = 100;
    private static final BigDecimal EXACT_EAN_SCORE =
            new BigDecimal("1.0000");
    private static final Pattern PACKAGE_WORD =
            Pattern.compile("(?<= )([0-9]+|l|ml|lit|g|gr|kg|kom|x)(?= )");

    private final CanonicalProductSearchRepository searchRepository;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ProductMatchScorer productMatchScorer;
    private final EanValidator eanValidator;
    private final ShoppingIntentResolver shoppingIntentResolver;

    public CanonicalProductSearchService(
            CanonicalProductSearchRepository searchRepository,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ProductMatchScorer productMatchScorer,
            EanValidator eanValidator,
            ShoppingIntentResolver shoppingIntentResolver
    ) {
        this.searchRepository = searchRepository;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.productMatchScorer = productMatchScorer;
        this.eanValidator = eanValidator;
        this.shoppingIntentResolver = shoppingIntentResolver;
    }

    public CanonicalProductSearchPage search(
            String query,
            int page,
            int limit
    ) {
        return search(query,page,limit,true);
    }

    public CanonicalProductSearchPage search(String query, int page, int limit, boolean includeWithoutPrice) {
        validate(query, page, limit);

        String strippedQuery = query.strip();
        List<ScoredRow> scoredRows = rank(query, includeWithoutPrice);

        int totalElements = scoredRows.size();
        long offset = (long) page * limit;
        int fromIndex = (int) Math.min(offset, totalElements);
        int toIndex = (int) Math.min(offset + limit, totalElements);

        List<ScoredRow> pageRows = scoredRows.subList(fromIndex, toIndex);
        var knownRetailers = searchRepository.findKnownRetailers(pageRows.stream()
                .map(row -> row.source().productFamilyId()).toList());
        Map<Long, List<ProductRetailerAvailability>> availabilityByFamily =
                searchRepository.findAvailability(
                                pageRows.stream()
                                        .map(row -> row.source()
                                                .productFamilyId())
                                        .toList()
                        ).stream()
                        .collect(Collectors.groupingBy(
                                CanonicalProductSearchRepository
                                        .ProductAvailabilityRow
                                        ::productFamilyId,
                                Collectors.mapping(
                                        CanonicalProductSearchRepository
                                                .ProductAvailabilityRow
                                                ::availability,
                                        Collectors.toList()
                                )
                        ));

        List<CanonicalProductSearchItem> items = pageRows.stream()
                .map(row -> toItem(
                        row,
                        availabilityByFamily.getOrDefault(
                                row.source().productFamilyId(),
                                List.of()
                        ),
                        knownRetailers.getOrDefault(row.source().productFamilyId(),List.of())
                ))
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

    // The shopping list's product check picks from these same ranked
    // products, so an item can match anything a shopper can find here.
    public List<ProductSearchCandidate> candidates(String query, int limit) {
        validate(query, 0, limit);

        return rank(query, true).stream()
                .limit(limit)
                .map(ScoredRow::source)
                .map(row -> new ProductSearchCandidate(
                        row.productFamilyId(),
                        row.canonicalProductId(),
                        row.name(),
                        row.brand(),
                        row.barcode(),
                        row.quantityValue(),
                        row.baseUnit(),
                        row.nameSimilarity(),
                        row.packageCount()
                ))
                .toList();
    }

    private List<ScoredRow> rank(String query, boolean includeWithoutPrice) {
        String normalizedQuery = productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        Optional<ParsedQuantity> queryQuantity =
                productQuantityParser.parse(query);

        String validEan = eanValidator.normalize(query.strip())
                .orElse(null);

        Map<Long, Integer> typePriorities = typePriorities(normalizedQuery);

        return searchRepository.findCandidates(
                        normalizedQuery,
                        validEan
                ).stream()
                .filter(row -> includeWithoutPrice || row.hasUsablePrice() || row.exactEanMatch())
                .map(row -> score(
                        normalizedQuery,
                        queryQuantity,
                        row
                ))
                .sorted(
                        Comparator.<ScoredRow, Boolean>comparing(row -> row.source().exactEanMatch(), Comparator.reverseOrder())
                                .thenComparing(row -> row.source().hasUsablePrice(), Comparator.reverseOrder())
                                .thenComparing(row -> typeRank(
                                        typePriorities,
                                        row.source()
                                ))
                                .thenComparing(
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
                                        .productFamilyId())
                )
                .toList();
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

    // Someone who types "mleko" wants milk, not chocolate milk or a lotion
    // that shares the word, so products of that type lead. Only when the
    // query names nothing but the thing and perhaps a size: "cokoladno mleko"
    // already says more than the type does and is left to the text.
    private Map<Long, Integer> typePriorities(String normalizedQuery) {
        return shoppingIntentResolver.resolve(normalizedQuery)
                .filter(intent -> PACKAGE_WORD.matcher(
                                (" " + normalizedQuery + " ").replace(
                                        " " + intent.normalizedAlias() + " ",
                                        " "
                                )
                        )
                        .replaceAll(" ")
                        .isBlank())
                .map(intent -> searchRepository.findProductTypePriorities(
                        intent.shoppingIntentId()
                ))
                .orElse(Map.of());
    }

    private static int typeRank(
            Map<Long, Integer> typePriorities,
            CanonicalProductSearchRow row
    ) {
        return row.productTypeId() == null
                ? Integer.MAX_VALUE
                : typePriorities.getOrDefault(
                        row.productTypeId(),
                        Integer.MAX_VALUE
                );
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

    private CanonicalProductSearchItem toItem(
            ScoredRow scoredRow,
            List<ProductRetailerAvailability> availability,
            List<String> knownRetailers
    ) {
        CanonicalProductSearchRow row = scoredRow.source();

        return new CanonicalProductSearchItem(
                row.productFamilyId(),
                row.canonicalProductId(),
                row.name(),
                row.brand(),
                row.barcode(),
                row.quantityValue(),
                row.baseUnit(),
                row.categoryCode(),
                row.categoryName(),
                row.variantCount(),
                availability,
                scoredRow.score(),
                row.hasUsablePrice(),
                knownRetailers,
                row.packageCount()
        );
    }

    private record ScoredRow(
            CanonicalProductSearchRow source,
            BigDecimal score
    ) {
    }
}
