package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EanValidatorTest {

    private final EanValidator validator = new EanValidator();

    @Test
    void acceptsValidEan8AndEan13() {
        assertThat(validator.isValid("96385074")).isTrue();
        assertThat(validator.isValid("8601234567899")).isTrue();
    }

    @Test
    void trimsValidEan() {
        assertThat(validator.normalize(" 8601234567899 "))
                .contains("8601234567899");
    }

    @Test
    void rejectsUnsupportedLengthAndNonDigits() {
        assertThat(validator.isValid("1234567")).isFalse();
        assertThat(validator.isValid("123456789012")).isFalse();
        assertThat(validator.isValid("860123456789A")).isFalse();
    }

    @Test
    void rejectsInvalidCheckDigit() {
        assertThat(validator.isValid("96385075")).isFalse();
        assertThat(validator.isValid("8601234567898")).isFalse();
    }

    @Test
    void rejectsBlankNullAndAllZeroValues() {
        assertThat(validator.isValid(null)).isFalse();
        assertThat(validator.isValid("   ")).isFalse();
        assertThat(validator.isValid("00000000")).isFalse();
        assertThat(validator.isValid("0000000000000")).isFalse();
    }
}
