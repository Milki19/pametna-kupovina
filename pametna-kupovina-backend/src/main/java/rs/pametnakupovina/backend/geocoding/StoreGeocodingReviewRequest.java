package rs.pametnakupovina.backend.geocoding;

public record StoreGeocodingReviewRequest(
        boolean accepted,
        Double correctedLatitude,
        Double correctedLongitude,
        String note
) {
}

