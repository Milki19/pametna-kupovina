package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnigramTokenizerTest {

    // Ids as in XLM-RoBERTa: <s> 0, <pad> 1, </s> 2, <unk> 3, then pieces.
    private static final String TOKENIZER = """
            {"model": {"type": "Unigram", "unk_id": 3, "vocab": [
              ["<s>", 0.0], ["<pad>", 0.0], ["</s>", 0.0], ["<unk>", 0.0],
              ["▁", -2.0], ["▁kob", -3.0], ["▁ko", -4.0], ["b", -4.0], ["▁pili", -3.5],
              ["▁pil", -3.0], ["i", -3.0], ["▁sir", -2.5], ["s", -5.0], ["r", -5.0]
            ]}}
            """;

    private static UnigramTokenizer tokenizer() throws IOException {
        return UnigramTokenizer.read(new ByteArrayInputStream(TOKENIZER.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void everyWordStartsWithTheSpaceMarkAfterNfkc() {
        // NFKC turns the ligature into two letters; a run of spaces is one.
        assertThat(UnigramTokenizer.words("kob  piﬁ")).containsExactly("▁kob", "▁pifi");
        assertThat(UnigramTokenizer.words("")).isEmpty();
    }

    @Test
    void takesTheSplitWithTheHighestTotalScore() throws IOException {
        UnigramTokenizer tokenizer = tokenizer();
        // "▁kob" (-3) beats "▁ko" + "b" (-8).
        assertThat(tokenizer.split("▁kob")).containsExactly(5);
        // "▁pili" (-3.5) beats "▁pil" + "i" (-6).
        assertThat(tokenizer.split("▁pili")).containsExactly(8);
    }

    @Test
    void charactersNoPieceCoversBecomeOneUnknownPiece() throws IOException {
        // "▁sir" then "čć", which no piece covers: one <unk>, not two.
        assertThat(tokenizer().split("▁sirčć")).containsExactly(11, 3);
    }

    @Test
    void wrapsInStartAndEndAndCutsToTheLimit() throws IOException {
        UnigramTokenizer tokenizer = tokenizer();
        assertThat(tokenizer.encode("kob pili", 64)).containsExactly(0, 5, 8, 2);
        assertThat(tokenizer.encode("kob pili sir", 4)).containsExactly(0, 5, 8, 2);
        assertThat(tokenizer.padId()).isEqualTo(1);
        assertThat(List.of(tokenizer.encode("", 8).length)).containsExactly(2);
    }
}
