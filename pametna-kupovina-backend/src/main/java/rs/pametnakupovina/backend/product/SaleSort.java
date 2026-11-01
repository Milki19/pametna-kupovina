package rs.pametnakupovina.backend.product;

/** How the list of sales is ordered. */
public enum SaleSort {
    /** The largest percentage off first. */
    DISCOUNT,
    /** The most money saved on one piece first. */
    SAVING,
    /** The lowest sale price first. */
    PRICE,
    /**
     * The closest shop first, then the largest percentage off. Without a
     * location it is the same as DISCOUNT.
     */
    NEAREST
}
