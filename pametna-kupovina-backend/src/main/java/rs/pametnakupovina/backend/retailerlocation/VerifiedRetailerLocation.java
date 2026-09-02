package rs.pametnakupovina.backend.retailerlocation;

public record VerifiedRetailerLocation(
        String externalCode,
        String name,
        String address,
        String city,
        String storeFormatCode,
        String storeFormatName,
        double latitude,
        double longitude,
        boolean active
) {
}
