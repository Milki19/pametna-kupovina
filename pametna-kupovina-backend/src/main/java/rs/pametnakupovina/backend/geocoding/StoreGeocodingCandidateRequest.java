package rs.pametnakupovina.backend.geocoding;

import java.math.BigDecimal;

public record StoreGeocodingCandidateRequest(
        double latitude,
        double longitude,
        BigDecimal confidence,
        String source,
        String sourceReference,
        String matchedAddress
) {
}

