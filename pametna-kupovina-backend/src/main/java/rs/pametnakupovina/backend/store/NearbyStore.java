package rs.pametnakupovina.backend.store;

public record NearbyStore(
        Long storeId,
        String retailerCode,
        String retailerName,
        String storeFormatCode,
        String storeFormatName,
        String externalCode,
        String storeName,
        String address,
        String city,
        double latitude,
        double longitude,
        double distanceMeters
) {
}
