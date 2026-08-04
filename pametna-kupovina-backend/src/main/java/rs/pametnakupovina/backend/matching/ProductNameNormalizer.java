package rs.pametnakupovina.backend.matching;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ProductNameNormalizer {

    private static final Pattern LETTER_NUMBER_BOUNDARY =
            Pattern.compile(
                    "(?<=[a-z])(?=[0-9])|(?<=[0-9])(?=[a-z])"
            );

    private static final Pattern NON_ALPHANUMERIC =
            Pattern.compile("[^a-z0-9]+");

    private static final Pattern MULTIPLE_SPACES =
            Pattern.compile("\\s+");

    public String normalize(String value) {
        String transliterated =
                SerbianTextTransliterator.toLatinAsciiLowercase(value)
                        .replace('×', 'x');

        String withSeparatedLettersAndNumbers =
                LETTER_NUMBER_BOUNDARY.matcher(transliterated)
                        .replaceAll(" ");

        String withoutPunctuation =
                NON_ALPHANUMERIC.matcher(
                                withSeparatedLettersAndNumbers
                        )
                        .replaceAll(" ");

        return MULTIPLE_SPACES.matcher(withoutPunctuation.strip())
                .replaceAll(" ");
    }
}
