package rs.pametnakupovina.backend.retailerlocation;

public record RetailerLocationResponse(
        Long id,
        String retailerCode,
        String retailerName,
        String locationName,
        String address,
        String city,
        double latitude,
        double longitude,
        double distanceKm
) {
}