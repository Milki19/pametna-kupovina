package rs.pametnakupovina.backend.geocoding;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record StoreGeocodingResult(
        Long storeId,
        String retailerCode,
        String externalCode,
        String address,
        String city,
        String query,
        String source,
        String sourceReference,
        String matchedAddress,
        Double candidateLatitude,
        Double candidateLongitude,
        Double appliedLatitude,
        Double appliedLongitude,
        BigDecimal confidence,
        StoreGeocodingStatus status,
        String suspiciousReason,
        String reviewNote,
        OffsetDateTime geocodedAt,
        OffsetDateTime reviewedAt,
        boolean cached,
        boolean coordinatesApplied
) {
}
