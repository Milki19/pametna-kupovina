package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The parser's built-in words are the rows V157 writes, and every row passes the table's checks. */
class UnitWordsMatchV157Test {

    private static final Pattern ROW = Pattern.compile(
            "\\(NULL, 'sr-Latn', '([a-z]+)', '([A-Z]+)', (NULL|'[a-z]+'), (\\d+), (TRUE|FALSE)\\)");

    @Test
    void theBuiltInWordsAreTheMigrationsRows() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(
                "/db/migration/V157__the_parser_reads_units_from_the_dictionary.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher row = ROW.matcher(sql);
        List<String> migration = new java.util.ArrayList<>();
        while (row.find()) {
            String base = row.group(3).equals("NULL") ? "null" : row.group(3).replace("'", "");
            migration.add(String.join(" ", row.group(1), row.group(2), base, row.group(4), row.group(5).toLowerCase()));
        }
        List<String> builtIn = BuiltInUnitWords.SERBIAN.stream()
                .map(word -> String.join(" ", word.word(), word.role().name(),
                        word.baseUnit() == null ? "null" : word.baseUnit().databaseValue(),
                        word.factor().stripTrailingZeros().toPlainString(), String.valueOf(word.insidePack())))
                .collect(Collectors.toList());
        assertThat(migration).hasSizeGreaterThan(40);
        assertThat(builtIn).containsExactlyInAnyOrderElementsOf(migration);
    }

    @Test
    void everyWordIsWrittenAsTheNormalizerWritesIt() {
        ProductNameNormalizer normalizer = new ProductNameNormalizer();
        for (UnitWord word : BuiltInUnitWords.SERBIAN) {
            assertThat(normalizer.normalize(word.word())).as(word.word()).isEqualTo(word.word());
            assertThat(word.factor()).isGreaterThan(BigDecimal.ZERO);
            assertThat(word.baseUnit() == null)
                    .as(word.word())
                    .isEqualTo(word.role() == UnitWord.Role.PACK || word.role() == UnitWord.Role.MULTIPLIER);
            assertThat(!word.insidePack() || word.role() == UnitWord.Role.COUNT).as(word.word()).isTrue();
        }
        assertThat(BuiltInUnitWords.SERBIAN).extracting(UnitWord::word).doesNotHaveDuplicates();
    }
}
