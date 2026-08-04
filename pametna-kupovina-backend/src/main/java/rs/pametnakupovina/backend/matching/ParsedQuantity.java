package rs.pametnakupovina.backend.matching;

import java.math.BigDecimal;
import java.util.Objects;

public record ParsedQuantity(
        BigDecimal value,
        BaseUnit unit
) {

    public ParsedQuantity {
        Objects.requireNonNull(value, "value ne sme biti null");
        Objects.requireNonNull(unit, "unit ne sme biti null");

        if (value.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Količina mora biti veća od nule"
            );
        }
    }
}
