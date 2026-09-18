package rs.pametnakupovina.backend.priceimport;

import java.time.Instant;

public record GovernmentDatasetCandidate(
        long id,
        String portalDatasetId,
        String slug,
        String title,
        String organizationName,
        String datasetPageUrl,
        String resourceId,
        String resourceTitle,
        String resourceUrl,
        String resourceFormat,
        Instant resourceLastModified,
        String reviewStatus,
        Instant firstDiscoveredAt,
        Instant lastDiscoveredAt,
        Instant probedAt,
        String probeVerdict,
        String probeSummary
) {
}
