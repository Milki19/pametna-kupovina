package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class ProductMatchScorer {

    /*
     * Produktna specifikacija definiše odnos 35:25:25 za naziv,
     * brend i pakovanje. Težine su normalizovane na 100% dok
     * kanonska kategorija ne postane raspoloživ scoring signal.
     */
    private static final BigDecimal NAME_WEIGHT =
            new BigDecimal("0.4118");

    private static final BigDecimal BRAND_WEIGHT =
            new BigDecimal("0.2941");

    private static final BigDecimal PACKAGE_WEIGHT =
            new BigDecimal("0.2941");

    private static final int SCORE_SCALE = 4;

    private final ProductNameNormalizer productNameNormalizer;

    public ProductMatchScorer(
            ProductNameNormalizer productNameNormalizer
    ) {
        this.productNameNormalizer = productNameNormalizer;
    }

    ProductMatchScore score(
            String normalizedQuery,
            Optional<ParsedQuantity> queryQuantity,
            BigDecimal nameSimilarity,
            String candidateBrand,
            BigDecimal candidateQuantity,
            String candidateBaseUnit
    ) {
        BigDecimal nameContribution = calculateNameContribution(
                nameSimilarity
        );

        BigDecimal brandContribution = calculateBrandContribution(
                normalizedQuery,
                candidateBrand
        );

        BigDecimal packageContribution = calculatePackageContribution(
                queryQuantity,
                candidateQuantity,
                candidateBaseUnit
        );

        BigDecimal totalScore = nameContribution
                .add(brandContribution)
                .add(packageContribution)
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);

        return new ProductMatchScore(
                totalScore,
                nameContribution,
                brandContribution,
                packageContribution,
                List.of(
                        explainName(nameSimilarity),
                        explainBrand(
                                candidateBrand,
                                brandContribution
                        ),
                        explainPackage(
                                queryQuantity,
                                candidateQuantity,
                                candidateBaseUnit,
                                packageContribution
                        )
                )
        );
    }

    private BigDecimal calculateNameContribution(
            BigDecimal nameSimilarity
    ) {
        BigDecimal boundedSimilarity = nameSimilarity
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE);

        return boundedSimilarity
                .multiply(NAME_WEIGHT)
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateBrandContribution(
            String normalizedQuery,
            String candidateBrand
    ) {
        if (!queryContainsBrand(normalizedQuery, candidateBrand)) {
            return zeroScore();
        }

        return BRAND_WEIGHT;
    }

    private BigDecimal calculatePackageContribution(
            Optional<ParsedQuantity> queryQuantity,
            BigDecimal candidateQuantity,
            String candidateBaseUnit
    ) {
        if (!packageMatches(
                queryQuantity,
                candidateQuantity,
                candidateBaseUnit
        )) {
            return zeroScore();
        }

        return PACKAGE_WEIGHT;
    }

    private boolean queryContainsBrand(
            String normalizedQuery,
            String candidateBrand
    ) {
        String normalizedBrand = productNameNormalizer.normalize(
                candidateBrand
        );

        if (normalizedBrand.isBlank()) {
            return false;
        }

        Set<String> queryTokens = new LinkedHashSet<>(
                Arrays.asList(normalizedQuery.split(" "))
        );

        return queryTokens.containsAll(
                Arrays.asList(normalizedBrand.split(" "))
        );
    }

    private boolean packageMatches(
            Optional<ParsedQuantity> queryQuantity,
            BigDecimal candidateQuantity,
            String candidateBaseUnit
    ) {
        if (queryQuantity.isEmpty()
                || candidateQuantity == null
                || candidateBaseUnit == null) {
            return false;
        }

        ParsedQuantity requestedQuantity = queryQuantity.orElseThrow();

        return requestedQuantity.value()
                .compareTo(candidateQuantity) == 0
                && requestedQuantity.unit().databaseValue()
                .equals(candidateBaseUnit);
    }

    private String explainName(BigDecimal nameSimilarity) {
        BigDecimal percentage = nameSimilarity
                .max(BigDecimal.ZERO)
                .min(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);

        return "Naziv: sličnost " + percentage + "%.";
    }

    private String explainBrand(
            String candidateBrand,
            BigDecimal contribution
    ) {
        if (candidateBrand == null || candidateBrand.isBlank()) {
            return "Brend: kandidat nema naveden brend.";
        }

        if (contribution.signum() > 0) {
            return "Brend: \"" + candidateBrand
                    + "\" je pronađen u upitu.";
        }

        return "Brend: \"" + candidateBrand
                + "\" nije pronađen u upitu.";
    }

    private String explainPackage(
            Optional<ParsedQuantity> queryQuantity,
            BigDecimal candidateQuantity,
            String candidateBaseUnit,
            BigDecimal contribution
    ) {
        if (queryQuantity.isEmpty()) {
            return "Pakovanje: količina nije prepoznata u upitu.";
        }

        if (candidateQuantity == null || candidateBaseUnit == null) {
            return "Pakovanje: kandidat nema normalizovanu količinu.";
        }

        if (contribution.signum() > 0) {
            return "Pakovanje: tačno podudaranje ("
                    + formatQuantity(candidateQuantity)
                    + " " + candidateBaseUnit + ").";
        }

        ParsedQuantity requestedQuantity = queryQuantity.orElseThrow();

        return "Pakovanje: traženo "
                + formatQuantity(requestedQuantity.value())
                + " " + requestedQuantity.unit().databaseValue()
                + ", kandidat ima "
                + formatQuantity(candidateQuantity)
                + " " + candidateBaseUnit + ".";
    }

    private String formatQuantity(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal zeroScore() {
        return BigDecimal.ZERO.setScale(SCORE_SCALE);
    }
}
