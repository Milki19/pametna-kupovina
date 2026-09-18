package rs.pametnakupovina.backend.priceimport.probe;

/** The gate a price list has to pass before its chain goes live. */
public enum PriceListProbeVerdict {

    /** Reads cleanly and is current: the chain can be registered. */
    READY,

    /** Readable, but something needs a person to look at it first. */
    NEEDS_REVIEW,

    /** Not a price list we can read at all. */
    REJECTED
}
