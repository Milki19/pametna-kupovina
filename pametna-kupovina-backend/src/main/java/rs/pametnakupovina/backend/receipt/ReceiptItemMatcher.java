package rs.pametnakupovina.backend.receipt;

import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.matching.FuzzyProductCandidateService;
import rs.pametnakupovina.backend.matching.ProductMatchStatus;
import rs.pametnakupovina.backend.matching.ProductMatchThresholdPolicy;

import java.util.Optional;

/**
 * Kasa piše artikal kako njoj odgovara („Burito sa piletinom"), a cenovnik
 * kako odgovara lancu. Gde se ta dva sigurno poklope, stavka sa računa dobija
 * svoj proizvod iz kataloga — to je jedino po čemu aplikacija kasnije može da
 * zna šta kupac zaista kupuje.
 *
 * <p>Ovde niko ne potvrđuje predlog: spaja se samo ono što bi i inače prošlo
 * bez pitanja. Ostalo ostaje nespojeno, što je na početku većina, i to nije
 * greška — račun i bez toga zna koliko je otišlo.
 */
@Component
public class ReceiptItemMatcher {

    private static final int CANDIDATES = 3;

    private final FuzzyProductCandidateService candidateService;
    private final ProductMatchThresholdPolicy thresholdPolicy;

    public ReceiptItemMatcher(
            FuzzyProductCandidateService candidateService,
            ProductMatchThresholdPolicy thresholdPolicy
    ) {
        this.candidateService = candidateService;
        this.thresholdPolicy = thresholdPolicy;
    }

    public Optional<Long> productFamilyFor(String receiptItemName) {
        if (receiptItemName == null || receiptItemName.isBlank()) {
            return Optional.empty();
        }

        try {
            return candidateService
                    .findCandidates(receiptItemName, CANDIDATES)
                    .stream()
                    .findFirst()
                    .filter(candidate -> candidate.productFamilyId() != null)
                    .filter(candidate -> thresholdPolicy.classify(
                            Optional.of(candidate.score().totalScore())
                    ) == ProductMatchStatus.AUTO_ACCEPTED)
                    .map(candidate -> candidate.productFamilyId());
        } catch (RuntimeException unmatchable) {
            // Naziv sa kase ume da bude i prazan skup znakova; račun zbog
            // toga ne sme da propadne.
            return Optional.empty();
        }
    }
}
