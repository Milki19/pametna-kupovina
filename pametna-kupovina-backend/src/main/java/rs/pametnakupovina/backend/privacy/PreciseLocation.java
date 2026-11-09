package rs.pametnakupovina.backend.privacy;

/**
 * Precizna lokacija korisnika čiji je životni vek ograničen na jedan zahtev.
 * Ovaj tip se ne sme koristiti u entitetima, keševima ili audit događajima,
 * osim zaokružen na oko kilometar ({@link #roughly()}): takav sme u deljeni
 * keš pretrage i akcija najviše pet minuta
 * ({@link PreciseLocationPolicy#useRoughlyForAWhile}).
 */
public record PreciseLocation(
        double latitude,
        double longitude
) {

    /**
     * Two decimals: 0,01° is 1,1 km north to south and about 0,8 km east
     * to west in Serbia. The apps already send it so for a search and for
     * Akcije; the server rounds again, so no other caller is kept exactly.
     */
    public PreciseLocation roughly() {
        return new PreciseLocation(round(latitude), round(longitude));
    }

    /** Already {@link #roughly()}: two decimals at most. */
    public boolean isRough() {
        return rough(latitude) && rough(longitude);
    }

    private static double round(double degrees) {
        return Math.round(degrees * 100) / 100.0;
    }

    private static boolean rough(double degrees) {
        double hundredths = degrees * 100;
        return Math.abs(hundredths - Math.rint(hundredths)) < 1e-6;
    }
}
