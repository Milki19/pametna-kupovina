package rs.pametnakupovina.backend.retailerlocation.univerexport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UniverexportLocationImportServiceTest {

    @Test
    void keepsOfficialFormatsSeparateFromUnverifiedPriceFormats() {
        UniverexportLocationImportService service =
                new UniverexportLocationImportService(
                        null,
                        null,
                        "https://univer"
                );

        var mapped = service.mapLocations(List.of(
                new UniverexportApiLocation(
                        "10033",
                        "MP033",
                        "Sentandrejski put bb",
                        "Novi Sad",
                        "Veliki",
                        45.27,
                        19.83,
                        true
                )
        ));

        assertThat(mapped).singleElement().satisfies(location -> {
            assertThat(location.externalCode()).isEqualTo("10033");
            assertThat(location.storeFormatCode())
                    .isEqualTo("UNIVEREXPORT_VELIKI");
        });
    }
}
