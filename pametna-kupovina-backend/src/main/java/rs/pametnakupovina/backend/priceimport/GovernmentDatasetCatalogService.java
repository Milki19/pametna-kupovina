package rs.pametnakupovina.backend.priceimport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

@Service
public class GovernmentDatasetCatalogService {

    private final GovernmentDataResourceDiscoveryClient discoveryClient;
    private final GovernmentDatasetCatalogRepository repository;
    private final String catalogApiUrl;
    private final int maximumPages;
    private final List<String> additionalApiUrls;

    public GovernmentDatasetCatalogService(
            GovernmentDataResourceDiscoveryClient discoveryClient,
            GovernmentDatasetCatalogRepository repository,
            @Value("${price-import.catalog.api-url}")
            String catalogApiUrl,
            @Value("${price-import.catalog.maximum-pages:10}")
            int maximumPages,
            @Value("${price-import.catalog.additional-api-urls:}")
            List<String> additionalApiUrls
    ) {
        this.discoveryClient = discoveryClient;
        this.repository = repository;
        this.catalogApiUrl = catalogApiUrl;
        this.maximumPages = maximumPages;
        this.additionalApiUrls = additionalApiUrls;
    }

    @Transactional
    public GovernmentDatasetCatalogSyncResult synchronize() {
        // The portal does not transliterate search terms or match all word forms.
        // Discover metadata only; candidates still require review before activation.
        var urls = new LinkedHashSet<String>();
        urls.add(catalogApiUrl);
        additionalApiUrls.stream().map(String::strip)
                .filter(url -> !url.isEmpty()).forEach(urls::add);
        Map<String, GovernmentPriceDataset> byId = new LinkedHashMap<>();
        for (String url : urls) {
            for (GovernmentPriceDataset candidate :
                    discoveryClient.discoverPriceDatasets(url, maximumPages)) {
                byId.merge(candidate.portalDatasetId(), candidate,
                        (previous, next) -> next.resourceLastModified()
                                .isAfter(previous.resourceLastModified()) ? next : previous);
            }
        }
        List<GovernmentPriceDataset> discovered = new ArrayList<>(byId.values());

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
