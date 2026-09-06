package rs.pametnakupovina.backend.retailerlocation.europrom;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EuropromLocationImportServiceTest {

    @Test
    void keepsLegacyCompatibleSlugsAndEuropromPriceFormat() {
        EuropromLocationImportService service =
                new EuropromLocationImportService(
                        null,
                        null,
                        "https://europrom.test/stores"
                );

        var mapped = service.mapLocations(List.of(
                new EuropromPageLocation(
                        "БРЂАНИ",
                        "Кнеза Милоша 100",
                        "Ваљево",
                        44.274,
                        19.880
                ),
                new EuropromPageLocation(
                        "ПОЋУТА",
                        "Село Поћута бб",
                        "Поћута",
                        44.100,
                        19.700
                )
        ));

        assertThat(mapped).extracting(location -> location.externalCode())
                .containsExactly("brdjani", "pocuta");
        assertThat(mapped).extracting(location -> location.storeFormatCode())
                .containsOnly("EUROPROM");
    }
}
