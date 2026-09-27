package rs.pametnakupovina.backend.priceimport.serbia;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.priceimport.ChainPriceSource;
import rs.pametnakupovina.backend.priceimport.ChainRefreshOutcome;
import rs.pametnakupovina.backend.priceimport.MarketPriceSources;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.priceimport.maxi.MaxiPriceImportCoordinator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Serbia's approved price sources; the daily cycle never enables new retailers. */
@Component
public class SerbianPriceSources implements MarketPriceSources {

    static final String MARKET = "RS";
    /** The five sources the freshness rule is about. */
    static final List<String> CORE = List.of("LIDL", "EUROPROM", "IDEA_RODA", "UNIVEREXPORT", "MAXI");
    /** Delhaize's chain-wide list for Maxi, beside Maxi's own store files. */
    static final String DELHAIZE_CATALOG = "MAXI_CATALOG";
    /**
     * Chains that publish one chain-wide list, often days apart: METRO, Super
     * Vero and Delhaize's catalogue. They were imported by hand, so their
     * prices went stale (Super Vero 11.09., METRO 13.09.). They run after the
     * core five; a failure warns but does not fail the day, and a list the
     * chain has not replaced yet is not a warning.
     */
    static final List<String> EXTRA = List.of(DELHAIZE_CATALOG, "METRO", "VEROPOULOS");
    private static final int MAXI_STORES = 6;

    private final PriceImportService imports;
    private final MaxiPriceImportCoordinator maxi;
    private final JdbcClient jdbcClient;

    public SerbianPriceSources(PriceImportService imports, MaxiPriceImportCoordinator maxi,
                               JdbcClient jdbcClient) {
        this.imports = imports;
        this.maxi = maxi;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public String marketCode() {
        return MARKET;
    }

    @Override
    public List<ChainPriceSource> chains() {
        List<ChainPriceSource> chains = new ArrayList<>();
        for (String code : CORE) {
            chains.add(code.equals(MaxiPriceImportCoordinator.RETAILER_CODE)
                    ? maxiStores()
                    : ChainPriceSource.dailyList(code, imports));
        }
        chains.add(ChainPriceSource.chainWideList(DELHAIZE_CATALOG, MaxiPriceImportCoordinator.RETAILER_CODE, imports));
        for (String code : EXTRA.subList(1, EXTRA.size())) {
            chains.add(ChainPriceSource.chainWideList(code, code, imports));
        }
        // Chains registered after a clean probe run last and, like the
        // other chain-wide lists, never fail the day on their own.
        for (String code : registeredChains()) {
            chains.add(ChainPriceSource.chainWideList(code, code, imports));
        }
        return chains;
    }

    @Override
    public String freshnessRule() {
        return "Svih pet osnovnih lanaca bez grešaka; cenovnik od danas ili juče. "
                + "METRO, Super Vero i Delhaize katalog idu posle njih; njihova greška je upozorenje.";
    }

    /** Chains onboarded from the portal: active, in Serbia, with the standard price list. */
    List<String> registeredChains() {
        List<String> known = Stream.concat(CORE.stream(), EXTRA.stream()).toList();

        return jdbcClient.sql("""
                        SELECT retailer.code
                          FROM app.retailer_data_source AS source
                          JOIN app.retailer AS retailer ON retailer.id = source.retailer_id
                          JOIN app.market AS market ON market.id = retailer.market_id
                         WHERE source.active
                           AND source.source_type = 'PRICE_CATALOG'
                           AND source.code = 'PRIMARY_PRICE_CATALOG'
                           AND market.code = :market
                         ORDER BY retailer.code
                        """)
                .param("market", MARKET)
                .query(String.class)
                .list()
                .stream()
                .filter(code -> !known.contains(code))
                .toList();
    }

    /** Maxi publishes a file per store; the day is clean only when all six are. */
    private ChainPriceSource maxiStores() {
        return new ChainPriceSource() {
            @Override
            public String code() {
                return MaxiPriceImportCoordinator.RETAILER_CODE;
            }

            @Override
            public boolean required() {
                return true;
            }

            @Override
            public ChainRefreshOutcome refresh(LocalDate today) {
                var result = maxi.importLatest();
                boolean allClean = result.filesFound() == MAXI_STORES && result.storesImported() == MAXI_STORES
                        && result.stores().stream().allMatch(store -> store.importResult() != null
                        && store.importResult().status().equals("SUCCEEDED"));
                int rows = result.stores().stream().filter(store -> store.importResult() != null)
                        .mapToInt(store -> store.importResult().rowsSaved()).sum();
                String status = result.storesImported() == 0 ? "FAILED"
                        : allClean ? "SUCCEEDED" : "SUCCEEDED_WITH_ERRORS";
                return new ChainRefreshOutcome(result.snapshotDate(), rows,
                        ChainRefreshOutcome.classify(status, result.snapshotDate(), today),
                        result.storesImported() + "/" + MAXI_STORES + " potvrđenih Maxi objekata; " + status);
            }
        };
    }
}
