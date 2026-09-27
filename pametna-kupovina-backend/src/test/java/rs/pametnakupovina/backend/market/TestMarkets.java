package rs.pametnakupovina.backend.market;

import java.math.BigDecimal;
import java.time.ZoneId;

/** The market V109 seeds, for tests that run without a database. */
public final class TestMarkets {

    private TestMarkets() {
    }

    public static Market serbia() {
        return new Market(
                1,
                "RS",
                "Srbija",
                "RSD",
                2,
                "sr-Latn-RS",
                "sr-Latn",
                ZoneId.of("Europe/Belgrade"),
                new BigDecimal("20.00"),
                new BigDecimal("400.00"),
                new BigDecimal("80.00")
        );
    }
}
