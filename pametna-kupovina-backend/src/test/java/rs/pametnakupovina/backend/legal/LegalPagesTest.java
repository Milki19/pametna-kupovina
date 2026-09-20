package rs.pametnakupovina.backend.legal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two texts Google Play asks for. A page still waiting on the owner for a
 * name, an e-mail or the country its data sits in must say on its face that it
 * is a draft — a legal page that reads as final while it is still full of
 * blanks is worse than no page at all.
 */
class LegalPagesTest {

    private static final Path PAGES =
            Path.of("src/main/resources/static");

    @Test
    void aPageWithBlanksLeftInItSaysItIsOnlyADraft() throws Exception {
        for (String page : new String[]{"privatnost", "uslovi"}) {
            String html = Files.readString(PAGES.resolve(page)
                    .resolve("index.html"));

            assertThat(html).contains("<link rel=\"stylesheet\" href=\"/pravno.css\">");

            if (html.contains("[dopuniti")) {
                assertThat(html)
                        .as("%s još čeka vlasnika, pa mora da nosi oznaku nacrta", page)
                        .contains("NACRT");
            } else {
                assertThat(html)
                        .as("%s je popunjen, pa oznaka nacrta više ne sme da stoji", page)
                        .doesNotContain("NACRT");
            }
        }
    }

    @Test
    void eachPageLinksToTheOther() throws Exception {
        assertThat(Files.readString(PAGES.resolve("privatnost/index.html")))
                .contains("href=\"/uslovi/\"");
        assertThat(Files.readString(PAGES.resolve("uslovi/index.html")))
                .contains("href=\"/privatnost/\"");
    }
}
