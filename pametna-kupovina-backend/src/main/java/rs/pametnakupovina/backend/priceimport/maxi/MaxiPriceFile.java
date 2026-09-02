package rs.pametnakupovina.backend.priceimport.maxi;

import java.time.LocalDate;

public record MaxiPriceFile(
        String storeExternalCode,
        String name,
        String url,
        LocalDate snapshotDate
) {
}
