package rs.pametnakupovina.backend.geocoding;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

record StoreGeocodingState(
        Long storeId,
        String retailerCode,
        String externalCode,
        String address,
        String city,
        Double candidateLatitude,
        Double candidateLongitude,
        Double appliedLatitude,
        Double appliedLongitude,
        String query,
        String source,
        String sourceReference,
        String matchedAddress,
        BigDecimal confidence,
        StoreGeocodingStatus status,
        String suspiciousReason,
        String reviewNote,
        OffsetDateTime geocodedAt,
        OffsetDateTime reviewedAt
) {
}

