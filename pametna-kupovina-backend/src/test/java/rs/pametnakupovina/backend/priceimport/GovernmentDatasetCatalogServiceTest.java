package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GovernmentDatasetCatalogServiceTest {
    private final GovernmentDataResourceDiscoveryClient client = mock(GovernmentDataResourceDiscoveryClient.class);
    private final GovernmentDatasetCatalogRepository repository = mock(GovernmentDatasetCatalogRepository.class);

    @Test
    void mergesScriptsAndWordFormsWithoutDuplicatingDatasetsOrQueries() {
        var older = dataset("same", "2026-09-01T00:00:00Z");
        var newer = dataset("same", "2026-09-09T00:00:00Z");
        var cyrillic = dataset("cyrillic", "2026-09-08T00:00:00Z");
        when(client.discoverPriceDatasets("latin", 10)).thenReturn(List.of(older));
        when(client.discoverPriceDatasets("cyrillic", 10)).thenReturn(List.of(newer, cyrillic));
        when(repository.count()).thenReturn(2);

        var service = new GovernmentDatasetCatalogService(client, repository,
                "latin", 10, List.of("cyrillic", "latin", " "));
        service.synchronize();

        verify(repository).upsertAll(List.of(newer, cyrillic));
        verify(client, times(1)).discoverPriceDatasets("latin", 10);
        verify(client, times(1)).discoverPriceDatasets("cyrillic", 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void doesNotPublishPartialDiscoveryWhenAnotherQueryFails() {
        when(client.discoverPriceDatasets("latin", 10))
                .thenReturn(List.of(dataset("one", "2026-09-01T00:00:00Z")));
        when(client.discoverPriceDatasets("cyrillic", 10))
                .thenThrow(new IllegalStateException("Portal unavailable"));
        var service = new GovernmentDatasetCatalogService(client, repository,
                "latin", 10, List.of("cyrillic"));

        assertThatThrownBy(service::synchronize).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void supportsSingleCustomCatalogWithoutAdditionalQueries() {
        when(client.discoverPriceDatasets("custom", 2)).thenReturn(List.of());
        var service = new GovernmentDatasetCatalogService(client, repository,
                "custom", 2, List.of());
        assertThat(service.synchronize()).isNotNull();
        verify(client).discoverPriceDatasets("custom", 2);
        verifyNoMoreInteractions(client);
    }

    private static GovernmentPriceDataset dataset(String id, String date) {
        return new GovernmentPriceDataset(id, id, "Ценовници " + id, "Retailer",
                "https://data.gov.rs/sr/datasets/" + id + "/", id,
                "cene.csv", "https://example.com/" + id + ".csv", "CSV", Instant.parse(date));
    }
}
