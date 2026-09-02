package rs.pametnakupovina.backend.retailerlocation;

public record RetailerLocationSource(
        String code,
        String parserProfile,
        String sourceUrl,
        String geocodingSource
) {
}
