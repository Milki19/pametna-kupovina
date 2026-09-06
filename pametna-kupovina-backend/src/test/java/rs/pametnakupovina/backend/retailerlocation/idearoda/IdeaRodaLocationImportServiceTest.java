package rs.pametnakupovina.backend.retailerlocation.idearoda;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportResult;
import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;
import rs.pametnakupovina.backend.storepricing.IdeaRodaPriceFormatMappingService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdeaRodaLocationImportServiceTest {

    @Test
    void mapsLocationFormatsToExplicitlyUnverifiedCodes() {
        IdeaRodaLocationImportService service =
                new IdeaRodaLocationImportService(
                        null,
                        null,
                        null,
                        "https://idea",
                        "https://roda"
                );

        var mapped = service.mapLocations(List.of(
                new IdeaRodaApiLocation(
                        "IDEA",
                        "IDEA_GEO_1_2",
                        "IDEA Adresa",
                        "Adresa",
                        "Srbija",
                        "IDEA super",
                        44.0,
                        20.0
                ),
                new IdeaRodaApiLocation(
                        "RODA",
                        "RODA_407",
                        "RODA 407",
                        "Adresa",
                        "Valjevo",
                        "RODA Mega",
                        44.2,
                        19.8
                )
        ));

        assertThat(mapped).extracting(location -> location.storeFormatCode())
                .containsExactly(
                        "IDEA_SUPER_LOCATION",
                        "RODA_MEGA_LOCATION"
                );
    }

    @Test
    void refreshesPriceFormatMappingsAfterSuccessfulLocationImport() {
        IdeaRodaLocationClient client = mock(
                IdeaRodaLocationClient.class
        );
        RetailerLocationImportService locationImportService = mock(
                RetailerLocationImportService.class
        );
        IdeaRodaPriceFormatMappingService mappingService = mock(
                IdeaRodaPriceFormatMappingService.class
        );
        when(client.fetchLocations()).thenReturn(List.of(
                new IdeaRodaApiLocation(
                        "RODA",
                        "RODA_407",
                        "RODA 407",
                        "Karađorđeva 62",
                        "Valjevo",
                        "RODA",
                        44.2,
                        19.8
                )
        ));
        RetailerLocationImportResult expected =
                new RetailerLocationImportResult(
                        "IDEA_RODA",
                        1,
                        1,
                        0,
                        "SUCCEEDED"
                );
        when(locationImportService.importVerifiedLocations(
                eq("IDEA_RODA"),
                any(),
                any()
        )).thenReturn(expected);

        IdeaRodaLocationImportService service =
                new IdeaRodaLocationImportService(
                        client,
                        locationImportService,
                        mappingService,
                        "https://idea",
                        "https://roda"
                );

        assertThat(service.importLatest()).isEqualTo(expected);
        verify(mappingService).importLatest();
    }
}
