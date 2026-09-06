package rs.pametnakupovina.backend.retailerlocation.europrom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EuropromLocationClientTest {

    @Test
    void parsesEurocenterAndRegularStoreCards() {
        String html = """
                <a data-tooltip="!2d19.8800!3d44.2740!"></a>
                <h3>Еуроцентар</h3>
                <i class="fas fa-map-marker-alt"></i>
                <div class=w-iconbox-title>Владике Николаја 27/а, Ваљево</div>
                <div class=w-separator-text><span>БРЂАНИ</span></div>
                <i class="fas fa-map-marker-alt"></i>
                <h6 class=w-iconbox-title>Кнеза Милоша 100, Ваљево</h6>
                <a data-tooltip="!2d19.8810!3d44.2750!"></a>
                <div class=w-separator-text><span>ПОЋУТА</span></div>
                <i class="fas fa-map-marker-alt"></i>
                <h6 class=w-iconbox-title>Село Поћута бб</h6>
                <a data-tooltip="!2d19.7000!3d44.1000!"></a>
                """;

        var locations = new EuropromLocationClient(
                null,
                "https://europrom.test/stores"
        ).parseLocations(html);

        assertThat(locations).hasSize(3);
        assertThat(locations).extracting(EuropromPageLocation::name)
                .containsExactly("Еуроцентар", "БРЂАНИ", "ПОЋУТА");
        assertThat(locations.getFirst().city()).isEqualTo("Ваљево");
        assertThat(locations.getLast().city()).isEqualTo("Поћута");
        assertThat(locations.get(1).latitude()).isEqualTo(44.2750);
        assertThat(locations.get(1).longitude()).isEqualTo(19.8810);
    }
}
