package rs.pametnakupovina.backend.priceimport.maxi;

import java.time.LocalDate;
import java.util.List;

public record MaxiLatestImportResult(
        LocalDate snapshotDate,
        int filesFound,
        int storesImported,
        String status,
        List<MaxiStoreImportResult> stores
) {
}
