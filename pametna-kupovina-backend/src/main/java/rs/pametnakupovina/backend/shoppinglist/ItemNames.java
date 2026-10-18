package rs.pametnakupovina.backend.shoppinglist;

import java.util.regex.Pattern;

/** What a typed item name keeps once the decoration around the words is gone. */
final class ItemNames {

    /**
     * Emoji and the marks copied with them from notes and chats ("🍅 paradajz",
     * "✔ hleb"): pictographs, dingbats, flags, skin tones, joiners and
     * presentation selectors. The same ranges are dropped by the web and the app.
     */
    private static final Pattern EMOJI = Pattern.compile(
            "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2300}-\\x{23FF}"
                    + "\\x{FE0E}\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]+"
    );

    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    private ItemNames() {
    }

    static String withoutEmoji(String value) {
        if (value == null) {
            return null;
        }
        String stripped = EMOJI.matcher(value).replaceAll(" ");
        return stripped.equals(value)
                ? value
                : SPACES.matcher(stripped).replaceAll(" ").trim();
    }
}
