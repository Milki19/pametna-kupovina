package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.junit.jupiter.api.Test;

import java.util.List;

import rs.pametnakupovina.backend.retailerlocation.RetailerLocationImportService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaxiLocationImportServiceTest {

    @Test
    void mapsLocatorIdsToPriceStoreCodes() {
        MaxiLocationImportService service =
                new MaxiLocationImportService(null, null, "https://maxi");

        var mapped = service.mapLocations(List.of(
                new MaxiApiLocation(
                        "S508",
                        "Maxi 508",
                        "Kneza Mihaila 84-86",
                        "Valjevo",
                        "MAXI",
                        44.274,
                        19.88
                ),
                new MaxiApiLocation(
                        "S512",
                        "Shop & Go 512",
                        "Obrena Nikolića 3",
                        "Valjevo",
                        "SHOPNGO",
                        44.27,
                        19.87
                )
        ));

        assertThat(mapped).extracting(location -> location.externalCode())
                .containsExactly("S508", "S512");
        assertThat(mapped).extracting(location -> location.storeFormatCode())
                .containsExactly("MAXI", "SHOPNGO");
    }

    @Test
    void leavesUnknownFormatAndCodeEmptyInsteadOfFailingTheChain() {
        MaxiLocationImportService service =
                new MaxiLocationImportService(null, null, "https://maxi");

        var mapped = service.mapLocations(List.of(
                new MaxiApiLocation(
                        "S508", "Maxi 508", "Adresa", "Valjevo",
                        "MAXI", 44.27, 19.88
                ),
                new MaxiApiLocation(
                        "S700", "Maxi Express 700", "Adresa", "Beograd",
                        "EXPRESS", 44.8, 20.4
                ),
                new MaxiApiLocation(
                        "X12", "Maxi", "Adresa", "Niš",
                        "MAXI", 43.3, 21.9
                )
        ));

        assertThat(mapped).hasSize(3);
        assertThat(mapped.get(0).storeFormatCode()).isEqualTo("MAXI");
        assertThat(mapped.get(1).storeFormatCode()).isNull();
        assertThat(mapped.get(1).storeFormatName()).isNull();
        assertThat(mapped.get(2).externalCode()).isNull();
    }

    @Test
    void marksSourceFailedWhenLocatorCannotBeRead() {
        MaxiLocationClient client = mock(MaxiLocationClient.class);
        RetailerLocationImportService importService =
                mock(RetailerLocationImportService.class);
        when(client.fetchLocations()).thenThrow(
                new IllegalStateException("Read timed out")
        );
        MaxiLocationImportService service = new MaxiLocationImportService(
                client,
                importService,
                "https://maxi"
        );

        assertThatThrownBy(service::importLatest)
                .hasMessage("Read timed out");
        verify(importService).recordVerifiedSourceFailure(
                eq("MAXI"),
                argThat(source -> "OFFICIAL_MAXI_STORE_LOCATOR"
                        .equals(source.code())),
                contains("Read timed out")
        );
        verify(importService, never())
                .importVerifiedLocations(any(), any(), any());
    }
}
