package rs.pametnakupovina.backend.priceimport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GovernmentDatasetCatalogService {

    private final GovernmentDataResourceDiscoveryClient discoveryClient;
    private final GovernmentDatasetCatalogRepository repository;
    private final String catalogApiUrl;
    private final int maximumPages;

    public GovernmentDatasetCatalogService(
            GovernmentDataResourceDiscoveryClient discoveryClient,
            GovernmentDatasetCatalogRepository repository,
            @Value("${price-import.catalog.api-url}")
            String catalogApiUrl,
            @Value("${price-import.catalog.maximum-pages:10}")
            int maximumPages
    ) {
        this.discoveryClient = discoveryClient;
        this.repository = repository;
        this.catalogApiUrl = catalogApiUrl;
        this.maximumPages = maximumPages;
    }

    @Transactional
    public GovernmentDatasetCatalogSyncResult synchronize() {
        List<GovernmentPriceDataset> discovered =
                discoveryClient.discoverPriceDatasets(
                        catalogApiUrl,
                        maximumPages
                );

        repository.upsertAll(discovered);

        return new GovernmentDatasetCatalogSyncResult(
                discovered.size(),
                repository.count()
        );
    }

    public List<GovernmentDatasetCandidate> findAll() {
        return repository.findAll();
    }
}
