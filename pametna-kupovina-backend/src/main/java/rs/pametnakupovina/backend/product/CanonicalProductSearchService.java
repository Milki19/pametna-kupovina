package rs.pametnakupovina.backend.product;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.FieldRefusal;
import rs.pametnakupovina.backend.matching.EanValidator;
import rs.pametnakupovina.backend.matching.ParsedQuantity;
import rs.pametnakupovina.backend.matching.ProductMatchScorer;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.matching.ProductQuantityParser;
import rs.pametnakupovina.backend.matching.SearchSpellingCorrector;
import rs.pametnakupovina.backend.privacy.PreciseLocation;
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
    private static final int MIN_SEARCH_LETTERS = 2;
    private static final BigDecimal EXACT_EAN_SCORE =
            new BigDecimal("1.0000");
    private static final int FAR_AWAY = 4;
    // "piletina oko 1kg" still names only chicken: "oko" and "cca" belong
    // to the amount, not to the thing.
    private static final Pattern PACKAGE_WORD =
            Pattern.compile("(?<= )([0-9]+|l|ml|lit|g|gr|kg|kom|komad|komada|x|oko|cca|otprilike)(?= )");
    // Food for a cat or a dog, a pizza or a ready meal share the words of
    // what a shop sells fresh ("Whiskas sa piletinom", "pica sa piletinom").
    // They come after the rest unless the query asks for them.
    private static final Pattern ANOTHER_AISLE_WORD = Pattern.compile(
            "\\b(whiskas|felix|friskies|kitekat|sheba|pedigree|purina|chappi|dreamies|macke|macka|mace"
                    + "|macje|maca|pse|psa|pas|pasa|psi|ljubim[a-z]*|cat|dog|pica|pizza|pizze"
                    // A ready meal or a cold cut made of it ("Carnex meksičko
                    // jelo sa piletinom", the shortened "jelosa", "Meso rol piletina") is not
                    // the meat.
                    + "|jel[ao][a-z]*|obrok|gotov[a-z]*|mac|sendvic[a-z]*|burger[a-z]*|tortilj[a-z]*|wrap"
                    + "|pastet[a-z]*|rol|narez[a-z]*|salam[a-z]*|virsl[a-z]*|parizer|hrenovk[a-z]*"
                    + "|kobasic[a-z]*|supa|supe|corba|kroket[a-z]*|nuget[a-z]*|nuggets)\\b");

    private final CanonicalProductSearchRepository searchRepository;
    private final ProductNameNormalizer productNameNormalizer;
    private final ProductQuantityParser productQuantityParser;
    private final ProductMatchScorer productMatchScorer;
    private final EanValidator eanValidator;
    private final ShoppingIntentResolver shoppingIntentResolver;
    private final SearchSpellingCorrector searchSpellingCorrector;

    public CanonicalProductSearchService(
            CanonicalProductSearchRepository searchRepository,
            ProductNameNormalizer productNameNormalizer,
            ProductQuantityParser productQuantityParser,
            ProductMatchScorer productMatchScorer,
            EanValidator eanValidator,
            ShoppingIntentResolver shoppingIntentResolver,
            SearchSpellingCorrector searchSpellingCorrector
    ) {
        this.searchRepository = searchRepository;
        this.productNameNormalizer = productNameNormalizer;
        this.productQuantityParser = productQuantityParser;
        this.productMatchScorer = productMatchScorer;
        this.eanValidator = eanValidator;
        this.shoppingIntentResolver = shoppingIntentResolver;
        this.searchSpellingCorrector = searchSpellingCorrector;
    }

    public CanonicalProductSearchPage search(
            String query,
            int page,
            int limit
    ) {
        return search(query,page,limit,true);
    }

    public CanonicalProductSearchPage search(String query, int page, int limit, boolean includeWithoutPrice) {
        return search(query, page, limit, includeWithoutPrice, null);
    }

    public CanonicalProductSearchPage search(
            String query,
            int page,
            int limit,
            boolean includeWithoutPrice,
            PreciseLocation near
    ) {
        return search(query, page, limit, includeWithoutPrice, near, false);
    }

    /**
     * @param near   gde je kupac; null = redosled bez blizine
     * @param onSale samo proizvodi koje neki lanac danas prodaje na akciji
     */
    public CanonicalProductSearchPage search(
            String query,
            int page,
            int limit,
            boolean includeWithoutPrice,
            PreciseLocation near,
            boolean onSale
    ) {
        validate(query, page, limit);
        requireSearchableLength(query);

        String strippedQuery = query.strip();
        RankedQuery ranked = rank(query, includeWithoutPrice, near);
        List<ScoredRow> scoredRows = ranked.rows();
        if (onSale) {
            var familiesOnSale = searchRepository.findFamiliesOnSale(
                    scoredRows.stream().map(row -> row.source().productFamilyId()).toList());
            scoredRows = scoredRows.stream()
                    .filter(row -> familiesOnSale.contains(row.source().productFamilyId()))
                    .toList();
        }

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

        // Da kupac pre izbora vidi da li se proizvod prodaje u blizini.
        Map<Long, Double> nearestMeters = near == null
                ? Map.of()
                : searchRepository.nearestStoreMeters(
                        pageRows.stream().map(row -> row.source().productFamilyId()).toList(),
                        near.latitude(),
                        near.longitude());

        List<CanonicalProductSearchItem> items = pageRows.stream()
                .map(row -> toItem(
                        row,
                        availabilityByFamily.getOrDefault(
                                row.source().productFamilyId(),
                                List.of()
                        ),
                        knownRetailers.getOrDefault(row.source().productFamilyId(),List.of()),
                        nearestMeters.get(row.source().productFamilyId())
                ))
                .toList();

        int totalPages = totalElements == 0
                ? 0
                : (int) (
                        ((long) totalElements + limit - 1) / limit
                );

        return new CanonicalProductSearchPage(
                strippedQuery,
                ranked.correctedQuery(),
                page,
                limit,
                totalElements,
                totalPages,
                (long) page + 1 < totalPages,
                items,
                near != null
        );
    }

    // The shopping list's product check picks from these same ranked
    // products, so an item can match anything a shopper can find here.
    public List<ProductSearchCandidate> candidates(String query, int limit) {
        validate(query, 0, limit);

        return rank(query, true, null).rows().stream()
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

    private RankedQuery rank(String query, boolean includeWithoutPrice, PreciseLocation near) {
        String normalizedQuery = productNameNormalizer.normalize(query);

        if (normalizedQuery.isBlank()) {
            throw new IllegalArgumentException(
                    "Parametar query mora sadržati slovo ili broj"
            );
        }

        String validEan = eanValidator.normalize(query.strip())
                .orElse(null);

        List<CanonicalProductSearchRow> rows = findRows(
                normalizedQuery,
                validEan,
                includeWithoutPrice
        );

        String correctedQuery = null;

        // Nothing at all: the query is no use as typed, so one round of
        // spelling correction can only help. A barcode is either right or
        // wrong and is never guessed at.
        if (rows.isEmpty() && validEan == null) {
            for (String corrected
                    : searchSpellingCorrector.corrections(normalizedQuery)) {
                List<CanonicalProductSearchRow> correctedRows = findRows(
                        corrected,
                        null,
                        includeWithoutPrice
                );

                // A correction that finds nothing either is not worth
                // putting in front of a shopper.
                if (!correctedRows.isEmpty()) {
                    correctedQuery = corrected;
                    normalizedQuery = corrected;
                    rows = correctedRows;
                    break;
                }
            }
        }

        Optional<ParsedQuantity> queryQuantity =
                productQuantityParser.parse(query);

        Optional<ShoppingIntentResolver.ResolvedShoppingIntent> kind =
                bareKind(normalizedQuery);
        Map<Long, Integer> typePriorities = kind
                .map(intent -> searchRepository.findProductTypePriorities(
                        intent.shoppingIntentId()
                ))
                .orElse(Map.of());
        if (kind.isPresent()) {
            rows = withKindFamilies(rows, normalizedQuery, kind.get(), includeWithoutPrice);
        }
        Map<Long, Integer> nearness = near == null && kind.isPresent()
                ? spread(rows)
                : nearness(rows, near);
        Map<Long, Integer> anotherAisle = anotherAisle(rows, normalizedQuery);
        String rankedQuery = normalizedQuery;

        List<ScoredRow> scoredRows = rows.stream()
                .map(row -> score(
                        rankedQuery,
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
                                .thenComparing(row -> anotherAisle.getOrDefault(
                                        row.source().productFamilyId(),
                                        0
                                ))
                                // Ono što je traženo ostaje ispred, a među
                                // tim prvo proizvodi iz lanaca u blizini.
                                .thenComparing(row -> nearness.getOrDefault(
                                        row.source().productFamilyId(),
                                        FAR_AWAY
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

        return new RankedQuery(correctedQuery, scoredRows);
    }

    /**
     * Pojas udaljenosti, ne metri: 300 m i 900 m su isto „blizu", pa unutar
     * pojasa i dalje odlučuje koliko naziv odgovara upitu.
     */
    private Map<Long, Integer> nearness(List<CanonicalProductSearchRow> rows, PreciseLocation near) {
        if (near == null) {
            return Map.of();
        }

        return searchRepository.nearestStoreMeters(
                        rows.stream().map(CanonicalProductSearchRow::productFamilyId).toList(),
                        near.latitude(),
                        near.longitude()
                ).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> band(entry.getValue())));
    }

    private static int band(double meters) {
        return meters <= 2_000 ? 0 : meters <= 5_000 ? 1 : meters <= 10_000 ? 2 : 3;
    }

    private List<CanonicalProductSearchRow> findRows(
            String normalizedQuery,
            String validEan,
            boolean includeWithoutPrice
    ) {
        return searchRepository.findCandidates(normalizedQuery, validEan)
                .stream()
                .filter(row -> includeWithoutPrice
                        || row.hasUsablePrice()
                        || row.exactEanMatch())
                .toList();
    }

    /**
     * One letter matched nearly the whole catalogue and took seven seconds,
     * so a search asks for two letters or digits. Matching a list item
     * (`candidates`) keeps working on whatever the item is called.
     */
    private static void requireSearchableLength(String query) {
        long letters = query.codePoints()
                .filter(Character::isLetterOrDigit)
                .limit(MIN_SEARCH_LETTERS)
                .count();
        if (letters < MIN_SEARCH_LETTERS) {
            throw new FieldRefusal(
                    "query",
                    "Upiši bar " + MIN_SEARCH_LETTERS + " slova ili broja."
            );
        }
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
    private Optional<ShoppingIntentResolver.ResolvedShoppingIntent> bareKind(String normalizedQuery) {
        return shoppingIntentResolver.resolve(normalizedQuery)
                .filter(intent -> PACKAGE_WORD.matcher(
                                (" " + normalizedQuery + " ").replace(
                                        " " + intent.normalizedAlias() + " ",
                                        " "
                                )
                        )
                        .replaceAll(" ")
                        .isBlank());
    }

    /**
     * "piletina" names chicken, but „Pileći batak“ in the big chains shares
     * no word with it: without the families of the kind, only small chains'
     * „Sveža piletina“ was found, far from the shopper.
     */
    private List<CanonicalProductSearchRow> withKindFamilies(
            List<CanonicalProductSearchRow> rows,
            String normalizedQuery,
            ShoppingIntentResolver.ResolvedShoppingIntent kind,
            boolean includeWithoutPrice
    ) {
        java.util.Set<Long> found = rows.stream()
                .map(CanonicalProductSearchRow::productFamilyId)
                .collect(Collectors.toSet());
        List<CanonicalProductSearchRow> more = searchRepository
                .findKindFamilies(normalizedQuery, kind.normalizedAlias())
                .stream()
                .filter(row -> !found.contains(row.productFamilyId()))
                .filter(row -> includeWithoutPrice || row.hasUsablePrice())
                .toList();
        if (more.isEmpty()) {
            return rows;
        }
        List<CanonicalProductSearchRow> all = new java.util.ArrayList<>(rows);
        all.addAll(more);
        return all;
    }

    /**
     * Where the shopper is not known yet (step 1 comes before the location),
     * what many chains sell is more likely near them than what one small
     * chain sells: a band, like distance, so the name still decides within it.
     */
    private static Map<Long, Integer> spread(List<CanonicalProductSearchRow> rows) {
        return rows.stream().collect(Collectors.toMap(
                CanonicalProductSearchRow::productFamilyId,
                row -> row.chainCount() >= 5 ? 0 : row.chainCount() >= 2 ? 1 : 2,
                (left, right) -> left
        ));
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

    private Map<Long, Integer> anotherAisle(
            List<CanonicalProductSearchRow> rows,
            String normalizedQuery
    ) {
        if (ANOTHER_AISLE_WORD.matcher(normalizedQuery).find()) {
            return Map.of();
        }
        return rows.stream()
                .filter(row -> ANOTHER_AISLE_WORD.matcher(
                        productNameNormalizer.normalize(row.name())).find())
                .collect(Collectors.toMap(
                        CanonicalProductSearchRow::productFamilyId,
                        row -> 1,
                        (left, right) -> left
                ));
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
            List<String> knownRetailers,
            Double nearestStoreMeters
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
                row.packageCount(),
                nearestStoreMeters
        );
    }

    private record RankedQuery(
            String correctedQuery,
            List<ScoredRow> rows
    ) {
    }

    private record ScoredRow(
            CanonicalProductSearchRow source,
            BigDecimal score
    ) {
    }
}
