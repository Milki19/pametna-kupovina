package rs.pametnakupovina.backend.retailerlocation.lidl;

record LidlApiLocation(
        String objectNumber,
        String storeName,
        Address address,
        Status status
) {

    record Address(
            String streetName,
            String streetNumber,
            String city,
            String zip,
            Double longitude,
            Double latitude
    ) {
    }

    record Status(
            String name,
            String from,
            String to
    ) {
    }
}
