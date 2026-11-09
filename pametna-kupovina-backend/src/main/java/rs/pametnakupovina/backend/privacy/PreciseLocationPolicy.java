package rs.pametnakupovina.backend.privacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Function;

/**
 * Jedina ulazna granica za korišćenje precizne korisničke lokacije.
 *
 * <p>Koordinate postoje samo kao lokalna vrednost tokom izvršavanja prosleđene
 * operacije. Politika ne poseduje repozitorijum, keš niti promenljivo stanje i
 * u log upisuje samo svrhu, retention pravilo i ishod.</p>
 *
 * <p>Izuzetak su pretraga i akcije ({@link #useRoughlyForAWhile}): lokacija se
 * odmah zaokruži na oko kilometar, i tako zaokružena sme da bude deo ključa
 * deljenog keša odgovora najviše {@link #ROUGH_RETENTION_MINUTES} minuta.</p>
 */
@Component
public final class PreciseLocationPolicy {

    public static final String RETENTION_POLICY = "REQUEST_ONLY";

    /** Rounded to about a kilometre and kept at most five minutes. */
    public static final String ROUGH_RETENTION_POLICY = "ROUNDED_1KM_5MIN";

    public static final int ROUGH_RETENTION_MINUTES = 5;

    private static final Logger log = LoggerFactory.getLogger(
            PreciseLocationPolicy.class
    );

    public <T> T useForRequest(
            PreciseLocationPurpose purpose,
            double latitude,
            double longitude,
            Function<PreciseLocation, T> operation
    ) {
        Objects.requireNonNull(purpose, "purpose je obavezan");
        Objects.requireNonNull(operation, "operation je obavezna");

        return use(
                purpose,
                RETENTION_POLICY,
                new PreciseLocation(latitude, longitude),
                operation
        );
    }

    /**
     * For a search and for Akcije: the same words or the same sales page from
     * the same neighbourhood are the same answer for everyone, so the
     * operation only ever sees the location rounded to about a kilometre,
     * and that rounded place may be part of a shared answer's key for
     * {@link #ROUGH_RETENTION_MINUTES} minutes. Nothing ties it to a phone.
     */
    public <T> T useRoughlyForAWhile(
            PreciseLocationPurpose purpose,
            double latitude,
            double longitude,
            Function<PreciseLocation, T> operation
    ) {
        Objects.requireNonNull(purpose, "purpose je obavezan");
        Objects.requireNonNull(operation, "operation je obavezna");

        return use(
                purpose,
                ROUGH_RETENTION_POLICY,
                new PreciseLocation(latitude, longitude).roughly(),
                operation
        );
    }

    private static <T> T use(
            PreciseLocationPurpose purpose,
            String retention,
            PreciseLocation location,
            Function<PreciseLocation, T> operation
    ) {
        try {
            T result = operation.apply(location);

            log.info(
                    "Precise location processed: purpose={}, retention={}, outcome=SUCCESS",
                    purpose,
                    retention
            );

            return result;
        } catch (RuntimeException exception) {
            log.info(
                    "Precise location processed: purpose={}, retention={}, outcome=REJECTED",
                    purpose,
                    retention
            );

            throw exception;
        }
    }
}
