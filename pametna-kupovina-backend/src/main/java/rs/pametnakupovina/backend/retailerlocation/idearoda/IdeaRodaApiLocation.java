package rs.pametnakupovina.backend.retailerlocation.idearoda;

record IdeaRodaApiLocation(
        String brand,
        String code,
        String name,
        String address,
        String city,
        String format,
        double latitude,
        double longitude
) {
}
