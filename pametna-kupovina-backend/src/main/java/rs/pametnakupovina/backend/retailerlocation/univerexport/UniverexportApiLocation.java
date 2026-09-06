package rs.pametnakupovina.backend.retailerlocation.univerexport;

record UniverexportApiLocation(
        String code,
        String name,
        String address,
        String city,
        String format,
        double latitude,
        double longitude,
        boolean active
) {
}
