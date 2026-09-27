package rs.pametnakupovina.backend.priceimport;

import java.time.LocalDate;

/** One chain's prices, as one step of its market's daily cycle. */
public interface ChainPriceSource {

    /** What the cycle records the outcome under, usually the retailer code. */
    String code();

    /** A required chain that fails fails the whole day; any other only warns. */
    boolean required();

    /** @param today the day where the chain's market is */
    ChainRefreshOutcome refresh(LocalDate today);

    /**
     * A chain that publishes a standard price list every day: it is required,
     * and only a list from today or yesterday counts as clean.
     */
    static ChainPriceSource dailyList(String retailerCode, PriceImportService imports) {
        return new ChainPriceSource() {
            @Override
            public String code() {
                return retailerCode;
            }

            @Override
            public boolean required() {
                return true;
            }

            @Override
            public ChainRefreshOutcome refresh(LocalDate today) {
                ImportResult result = imports.importPrices(retailerCode);
                return new ChainRefreshOutcome(result.snapshotDate(), result.rowsSaved(),
                        ChainRefreshOutcome.classify(result.status(), result.snapshotDate(), today),
                        result.status());
            }
        };
    }

    /**
     * A chain-wide list the chain replaces when it likes, often days apart:
     * as fresh as the chain publishes it, and its failure only warns.
     *
     * @param code         what the cycle records it under
     * @param retailerCode whose standard price list is imported
     */
    static ChainPriceSource chainWideList(String code, String retailerCode, PriceImportService imports) {
        return new ChainPriceSource() {
            @Override
            public String code() {
                return code;
            }

            @Override
            public boolean required() {
                return false;
            }

            @Override
            public ChainRefreshOutcome refresh(LocalDate today) {
                ImportResult result = imports.importPrices(retailerCode);
                return new ChainRefreshOutcome(result.snapshotDate(), result.rowsSaved(),
                        ChainRefreshOutcome.classifyChainWide(result.status()),
                        result.status() + "; cenovnik od " + result.snapshotDate());
            }
        };
    }
}
