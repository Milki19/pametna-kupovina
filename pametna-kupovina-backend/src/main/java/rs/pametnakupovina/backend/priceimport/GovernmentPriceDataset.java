package rs.pametnakupovina.backend.priceimport;

import java.time.Instant;

public record GovernmentPriceDataset(
        String portalDatasetId,
        String slug,
        String title,
        String organizationName,
        String datasetPageUrl,
        String resourceId,
        String resourceTitle,
        String resourceUrl,
        String resourceFormat,
        Instant resourceLastModified
) {
}
