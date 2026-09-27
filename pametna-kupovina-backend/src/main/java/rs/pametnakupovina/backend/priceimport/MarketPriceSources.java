package rs.pametnakupovina.backend.priceimport;

import java.util.List;

/**
 * Where one market's prices come from. A country the app launches in brings
 * one of these as a Spring bean, with a source per chain; the daily cycle,
 * its evidence, its lock and its clock are the same for every market and
 * follow the market's own time zone.
 */
public interface MarketPriceSources {

    /** ISO 3166 code of the market, as in {@code app.market.code}. */
    String marketCode();

    /**
     * The chains of one daily cycle, in the order they run. Asked at the
     * start of every cycle, so a chain registered since the last one is in.
     */
    List<ChainPriceSource> chains();

    /** What a clean day means in this market, shown beside the cycles. */
    String freshnessRule();
}
