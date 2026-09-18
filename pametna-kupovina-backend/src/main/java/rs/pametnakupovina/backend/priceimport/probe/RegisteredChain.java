package rs.pametnakupovina.backend.priceimport.probe;

/** A chain that now has a price source the daily cycle will import. */
public record RegisteredChain(
        String retailerCode,
        String retailerName,
        long retailerId,
        boolean priceSourceCreated
) {
}
