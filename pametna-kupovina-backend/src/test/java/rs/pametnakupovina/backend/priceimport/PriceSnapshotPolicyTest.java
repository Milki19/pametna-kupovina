package rs.pametnakupovina.backend.priceimport;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class PriceSnapshotPolicyTest {
    @Test
    void rejectsOldFallbackButAcceptsCutoverDayAndNewer() {
        var policy = new PriceSnapshotPolicy("2026-09-01");
        assertThatThrownBy(() -> policy.requireAccepted(LocalDate.of(2026, 3, 2)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2026-09-01");
        assertThatCode(() -> policy.requireAccepted(LocalDate.of(2026, 9, 1))).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireAccepted(LocalDate.of(2026, 9, 9))).doesNotThrowAnyException();
    }

    @Test
    void historicalRestoreRequiresExplicitlyDisabledBoundary() {
        var policy = new PriceSnapshotPolicy("");
        assertThatCode(() -> policy.requireAccepted(LocalDate.of(2026, 3, 2))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireAccepted(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConfigurationFailsClosed() {
        assertThatThrownBy(() -> new PriceSnapshotPolicy("bad-date"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void volumeGateRejectsAFeedThatWouldEraseMostOffers() {
        assertThatThrownBy(() -> PriceImportSafety.requireVolume(49, 100, new java.math.BigDecimal("0.5")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VOLUME_DROP");
        assertThatCode(() -> PriceImportSafety.requireVolume(50, 100, new java.math.BigDecimal("0.5")))
                .doesNotThrowAnyException();
        assertThatCode(() -> PriceImportSafety.requireVolume(59172, 157732, 19712,
                new java.math.BigDecimal("0.6"))).doesNotThrowAnyException();
    }
}
