package rs.pametnakupovina.backend.priceimport;

import java.util.regex.Pattern;

/**
 * Names as a shopper reads them, cleaned once at import (V124 cleans the rows
 * imported before, with the same rules). Keys and fingerprints are built from
 * the chain's own text, so only the stored name changes.
 */
public final class DisplayNames {

    // \s alone misses the no-break and thin spaces some chains' files carry.
    private static final Pattern SPACES = Pattern.compile(
            "[\\s\\u00A0\\u1680\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]+"
    );
    // "213 Orasac": the chain's internal shop number in front.
    private static final Pattern STORE_NUMBER = Pattern.compile(
            "^[0-9]{2,}\\s*[-.:]?\\s+"
    );
    // "369680 Dove gel 700 ml": a stock code in front (V81).
    private static final Pattern STOCK_CODE = Pattern.compile("^[0-9]{5,}\\s+");

    private DisplayNames() {
    }

    public static String cleanSpaces(String value) {
        if (value == null) {
            return null;
        }
        return SPACES.matcher(value).replaceAll(" ").strip();
    }

    public static String storeName(String value) {
        return withoutPrefix(value, STORE_NUMBER);
    }

    public static String productName(String value) {
        return withoutPrefix(value, STOCK_CODE);
    }

    private static String withoutPrefix(String value, Pattern prefix) {
        String spaced = cleanSpaces(value);
        if (spaced == null) {
            return null;
        }
        String cleaned = prefix.matcher(spaced).replaceFirst("");
        return cleaned.isEmpty() ? spaced : cleaned;
    }
}
