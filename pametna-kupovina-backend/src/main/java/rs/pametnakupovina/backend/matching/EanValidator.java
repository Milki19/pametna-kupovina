package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EanValidator {

    private static final int EAN_8_LENGTH = 8;
    private static final int EAN_13_LENGTH = 13;

    public Optional<String> normalize(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String normalized = value.strip();

        if (!hasSupportedLength(normalized)
                || !containsOnlyDigits(normalized)
                || containsOnlyZeros(normalized)
                || !hasValidCheckDigit(normalized)) {
            return Optional.empty();
        }

        return Optional.of(normalized);
    }

    public boolean isValid(String value) {
        return normalize(value).isPresent();
    }

    private boolean hasSupportedLength(String value) {
        return value.length() == EAN_8_LENGTH
                || value.length() == EAN_13_LENGTH;
    }

    private boolean containsOnlyDigits(String value) {
        return value.chars().allMatch(
                character -> character >= '0' && character <= '9'
        );
    }

    private boolean containsOnlyZeros(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    private boolean hasValidCheckDigit(String value) {
        int bodyLength = value.length() - 1;
        int weightedSum = 0;

        for (int index = 0; index < bodyLength; index++) {
            int digit = Character.digit(value.charAt(index), 10);
            int distanceFromRight = bodyLength - index;
            int weight = distanceFromRight % 2 == 1 ? 3 : 1;

            weightedSum += digit * weight;
        }

        int expectedCheckDigit =
                (10 - weightedSum % 10) % 10;

        int actualCheckDigit = Character.digit(
                value.charAt(bodyLength),
                10
        );

        return actualCheckDigit == expectedCheckDigit;
    }
}
