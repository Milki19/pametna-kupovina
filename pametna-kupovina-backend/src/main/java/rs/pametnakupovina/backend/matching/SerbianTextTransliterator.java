package rs.pametnakupovina.backend.matching;

import java.text.Normalizer;
import java.util.Locale;

final class SerbianTextTransliterator {

    private SerbianTextTransliterator() {
    }

    static String toLatinAsciiLowercase(String value) {
        if (value == null) {
            return "";
        }

        String lowerCaseValue = value.toLowerCase(Locale.ROOT);
        StringBuilder latinValue = new StringBuilder(
                lowerCaseValue.length()
        );

        for (int index = 0; index < lowerCaseValue.length(); index++) {
            appendLatinEquivalent(
                    latinValue,
                    lowerCaseValue.charAt(index)
            );
        }

        return Normalizer.normalize(
                        latinValue,
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}+", "")
                .replace("đ", "dj");
    }

    private static void appendLatinEquivalent(
            StringBuilder target,
            char character
    ) {
        switch (character) {
            case 'а' -> target.append('a');
            case 'б' -> target.append('b');
            case 'в' -> target.append('v');
            case 'г' -> target.append('g');
            case 'д' -> target.append('d');
            case 'ђ' -> target.append("dj");
            case 'е' -> target.append('e');
            case 'ж' -> target.append('z');
            case 'з' -> target.append('z');
            case 'и' -> target.append('i');
            case 'ј' -> target.append('j');
            case 'к' -> target.append('k');
            case 'л' -> target.append('l');
            case 'љ' -> target.append("lj");
            case 'м' -> target.append('m');
            case 'н' -> target.append('n');
            case 'њ' -> target.append("nj");
            case 'о' -> target.append('o');
            case 'п' -> target.append('p');
            case 'р' -> target.append('r');
            case 'с' -> target.append('s');
            case 'т' -> target.append('t');
            case 'ћ' -> target.append('c');
            case 'у' -> target.append('u');
            case 'ф' -> target.append('f');
            case 'х' -> target.append('h');
            case 'ц' -> target.append('c');
            case 'ч' -> target.append('c');
            case 'џ' -> target.append("dz");
            case 'ш' -> target.append('s');
            default -> target.append(character);
        }
    }
}
