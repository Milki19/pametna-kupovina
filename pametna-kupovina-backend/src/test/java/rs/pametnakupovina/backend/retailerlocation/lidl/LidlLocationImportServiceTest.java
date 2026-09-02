package rs.pametnakupovina.backend.retailerlocation.lidl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LidlLocationImportServiceTest {

    private final LidlLocationImportService service =
            new LidlLocationImportService(
                    null,
                    null,
                    "https://live.api/stores"
            );

    @Test
    void mapsOfficialStoreToPriceCapableFormat() {
        var locations = service.mapLocations(List.of(
                new LidlApiLocation(
                        "RS00152",
                        "Valjevo",
                        new LidlApiLocation.Address(
                                "Pop Lukina",
                                "45",
                                "Valjevo",
                                "14000",
                                19.88163,
                                44.27454
                        ),
                        new LidlApiLocation.Status(
                                "open",
                                null,
                                null
                        )
                )
        ));

        assertThat(locations).singleElement().satisfies(location -> {
            assertThat(location.externalCode()).isEqualTo("RS00152");
            assertThat(location.name()).isEqualTo("Lidl Valjevo");
            assertThat(location.address()).isEqualTo("Pop Lukina 45");
            assertThat(location.storeFormatCode()).isEqualTo("LIDL_KD");
            assertThat(location.storeFormatName())
                    .isEqualTo("Lidl Srbija KD");
            assertThat(location.active()).isTrue();
        });
    }
}
