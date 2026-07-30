package rs.pametnakupovina.backend.shoppinglist;

public record RouteStopResponse(
        int order,
        String retailerCode,
        Long locationId,
        String locationName,
        String city,
        double latitude,
        double longitude,
        double distanceFromPreviousKm
) {
}