package rs.pametnakupovina.backend.privacy;

/**
 * Precizna lokacija korisnika čiji je životni vek ograničen na jedan zahtev.
 * Ovaj tip se ne sme koristiti u entitetima, keševima ili audit događajima.
 */
public record PreciseLocation(
        double latitude,
        double longitude
) {
}
