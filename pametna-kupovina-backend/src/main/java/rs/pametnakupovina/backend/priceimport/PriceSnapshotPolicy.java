package rs.pametnakupovina.backend.priceimport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Prevent a fallback URL from reintroducing pre-cutover price snapshots. */
@Component
public class PriceSnapshotPolicy {
    private final LocalDate minimumDate;

    public PriceSnapshotPolicy(
            @Value("${price-import.minimum-snapshot-date:}") String configuredDate) {
        minimumDate = configuredDate == null || configuredDate.isBlank()
                ? null : LocalDate.parse(configuredDate.strip());
    }

    public void requireAccepted(LocalDate snapshotDate) {
        if (snapshotDate == null) {
            throw new IllegalArgumentException("Cenovnik nema potvrđen datum.");
        }
        if (minimumDate != null && snapshotDate.isBefore(minimumDate)) {
            throw new IllegalArgumentException("Cenovnik od " + snapshotDate
                    + " je stariji od dozvoljenog datuma " + minimumDate
                    + "; postojeće cene nisu zamenjene ovim cenovnikom.");
        }
    }
}
