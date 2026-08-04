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
 */
@Component
public final class PreciseLocationPolicy {

    public static final String RETENTION_POLICY = "REQUEST_ONLY";

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

        PreciseLocation location = new PreciseLocation(
                latitude,
                longitude
        );

        try {
            T result = operation.apply(location);

            log.info(
                    "Precise location processed: purpose={}, retention={}, outcome=SUCCESS",
                    purpose,
                    RETENTION_POLICY
            );

            return result;
        } catch (RuntimeException exception) {
            log.info(
                    "Precise location processed: purpose={}, retention={}, outcome=REJECTED",
                    purpose,
                    RETENTION_POLICY
            );

            throw exception;
        }
    }
}
