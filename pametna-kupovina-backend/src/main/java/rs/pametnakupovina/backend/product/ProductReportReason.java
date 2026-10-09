package rs.pametnakupovina.backend.product;

public enum ProductReportReason {
    WRONG_PRICE,
    NOT_SAME_PRODUCT,
    OTHER,
    /** Kupac u radnji ne nalazi proizvod na polici (kupovina po planu). */
    NOT_IN_STORE
}
