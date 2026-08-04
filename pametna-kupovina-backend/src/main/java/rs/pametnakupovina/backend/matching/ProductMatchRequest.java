package rs.pametnakupovina.backend.matching;

public record ProductMatchRequest(
        String query,
        Integer limit
) {

    private static final int DEFAULT_LIMIT = 5;

    public int resolvedLimit() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
