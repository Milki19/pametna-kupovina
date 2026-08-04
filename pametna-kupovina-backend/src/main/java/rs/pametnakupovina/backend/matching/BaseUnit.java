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
}
