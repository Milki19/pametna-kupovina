package rs.pametnakupovina.backend.matching;

public enum BaseUnit {

    GRAM("g"),
    MILLILITER("ml"),
    PIECE("piece");

    private final String databaseValue;

    BaseUnit(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public String databaseValue() {
        return databaseValue;
    }

    /** "g", "ml" or "piece", as the catalogue's columns write it. */
    public static BaseUnit fromDatabase(String value) {
        for (BaseUnit unit : values()) {
            if (unit.databaseValue.equals(value)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("Nepoznata jedinica: " + value);
    }
}
