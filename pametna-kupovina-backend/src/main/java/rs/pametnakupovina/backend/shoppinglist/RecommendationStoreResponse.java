package rs.pametnakupovina.backend.shoppinglist;

public record RecommendationStoreResponse(
        int stopOrder,
        Long storeId,
        String retailerCode,
        String retailerName,
        String storeFormatCode,
        String storeFormatName,
        String storeName,
        String address,
        String city,
        double latitude,
        double longitude,
        double distanceFromPreviousKm,
        long durationFromPreviousSeconds
) {
}
