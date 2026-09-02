package rs.pametnakupovina.backend.priceimport.maxi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import rs.pametnakupovina.backend.priceimport.ImportResult;
import rs.pametnakupovina.backend.priceimport.PriceImportService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
public class MaxiPriceImportCoordinator {

    private final MaxiPriceFeedClient feedClient;
    private final PriceImportService priceImportService;
    private final ZoneId zoneId;

    public MaxiPriceImportCoordinator(
            MaxiPriceFeedClient feedClient,
            PriceImportService priceImportService,
            @Value("${maxi.price-import.zone:Europe/Belgrade}")
            String zone
    ) {
        this.feedClient = feedClient;
        this.priceImportService = priceImportService;
        this.zoneId = ZoneId.of(zone);
    }

    public MaxiLatestImportResult importLatest() {
        List<MaxiPriceFile> files = feedClient.findLatestFiles(
                LocalDate.now(zoneId)
        );
        List<MaxiStoreImportResult> storeResults = new ArrayList<>();
        int storesImported = 0;

        for (MaxiPriceFile file : files) {
            try {
                ImportResult importResult =
                        priceImportService.importStorePrices(
                                "MAXI",
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
