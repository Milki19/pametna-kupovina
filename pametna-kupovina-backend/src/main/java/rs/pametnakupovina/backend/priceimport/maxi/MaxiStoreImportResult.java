package rs.pametnakupovina.backend.priceimport.maxi;

import rs.pametnakupovina.backend.priceimport.ImportResult;

public record MaxiStoreImportResult(
        String storeExternalCode,
        String sourceFile,
        ImportResult importResult,
        String error
) {
}
