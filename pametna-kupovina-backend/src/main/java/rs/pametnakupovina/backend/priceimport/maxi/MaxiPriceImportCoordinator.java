package rs.pametnakupovina.backend.priceimport.maxi;

import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.market.MarketRepository;
import rs.pametnakupovina.backend.priceimport.ImportResult;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.util.ArrayList;
import java.util.List;

@Service
public class MaxiPriceImportCoordinator {

    public static final String RETAILER_CODE = "MAXI";

    private final MaxiPriceFeedClient feedClient;
    private final PriceImportService priceImportService;
    private final MarketRepository marketRepository;

    public MaxiPriceImportCoordinator(
            MaxiPriceFeedClient feedClient,
            PriceImportService priceImportService,
            MarketRepository marketRepository
    ) {
        this.feedClient = feedClient;
        this.priceImportService = priceImportService;
        this.marketRepository = marketRepository;
    }

    public MaxiLatestImportResult importLatest() {
        // Maxi names its files by the day in its own market.
        List<MaxiPriceFile> files = feedClient.findLatestFiles(
                marketRepository.forRetailerCode(RETAILER_CODE).today()
        );
        List<MaxiStoreImportResult> storeResults = new ArrayList<>();
        int storesImported = 0;

        for (MaxiPriceFile file : files) {
            try {
                ImportResult importResult =
                        priceImportService.importStorePrices(
                                RETAILER_CODE,
                                file.storeExternalCode(),
                                file.url(),
                                file.snapshotDate()
                        );

                storeResults.add(new MaxiStoreImportResult(
                        file.storeExternalCode(),
                        file.name(),
                        importResult,
                        null
                ));
                storesImported++;
            } catch (RuntimeException exception) {
                storeResults.add(new MaxiStoreImportResult(
                        file.storeExternalCode(),
                        file.name(),
                        null,
                        exception.getMessage()
                ));
            }
        }

        String status;
        if (storesImported == files.size()) {
            status = "SUCCEEDED";
        } else if (storesImported == 0) {
            status = "FAILED";
        } else {
            status = "SUCCEEDED_WITH_ERRORS";
        }

        return new MaxiLatestImportResult(
                files.getFirst().snapshotDate(),
                files.size(),
                storesImported,
                status,
                List.copyOf(storeResults)
        );
    }
}
