package rs.pametnakupovina.backend.retailerlocation.maxi;

record MaxiApiLocation(
        String code,
        String name,
        String address,
        String city,
        String storeType,
        double latitude,
        double longitude
) {
}
