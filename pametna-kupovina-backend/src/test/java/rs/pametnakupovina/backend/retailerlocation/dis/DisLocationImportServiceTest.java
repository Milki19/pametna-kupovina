package rs.pametnakupovina.backend.retailerlocation.dis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DisLocationImportServiceTest {

    private final DisLocationImportService service =
            new DisLocationImportService(
                    null,
                    null,
                    "https://www.dis.rs/api/Dis/Locations"
            );

    @Test
    void mapsOfficialTypesToPriceFormats() {
        var locations = service.mapLocations(List.of(
                new DisApiLocation(
                        "28000",
                        "Jagodina",
                        "Filipa Stankovića 1",
                        "JAGODINA",
                        "07-22",
                        "dis",
                        "43.995346",
                        "21.264939"
                ),
                new DisApiLocation(
                        "90031",
                        "Bela Crkva 2",
                        "Jovana Popovića 3",
                        "BELA CRKVA",
                        "07-22",
                        "superdis",
                        "44.9000",
                        "21.4200"
                )
        ));

        assertThat(locations).hasSize(2);
        assertThat(locations.get(0).storeFormatCode())
                .isEqualTo("DIS_STANDARD_PLUS");
        assertThat(locations.get(0).storeFormatName())
                .isEqualTo("Dis Standard +");
        assertThat(locations.get(1).storeFormatCode())
                .isEqualTo("DIS_SUPER");
        assertThat(locations.get(1).name())
                .isEqualTo("Super DIS Bela Crkva 2");
    }
}
