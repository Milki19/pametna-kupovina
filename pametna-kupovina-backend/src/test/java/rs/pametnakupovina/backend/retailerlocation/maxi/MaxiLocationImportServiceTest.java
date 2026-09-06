package rs.pametnakupovina.backend.retailerlocation.maxi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
