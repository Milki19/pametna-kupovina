package rs.pametnakupovina.backend.matching;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.product.CatalogMemo;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** {@link KindTaxonomy} from product_type, product_type_required_attribute and variant_word. */
@Component
public class DatabaseKindTaxonomy implements KindTaxonomy {

    private final JdbcClient jdbcClient;
    private final CatalogMemo memo;

    public DatabaseKindTaxonomy(JdbcClient jdbcClient, CatalogMemo memo) {
        this.jdbcClient = jdbcClient;
        this.memo = memo;
    }

    @Override
    public long baseKindOf(long productTypeId) {
        return memo.remember(List.of("baseKind", productTypeId), () -> jdbcClient.sql("""
                        SELECT COALESCE(base_type_id, id) FROM app.product_type WHERE id = :id
                        """)
                .param("id", productTypeId)
                .query(Long.class)
                .optional()
                .orElse(productTypeId));
    }

    @Override
    public Set<String> requiredAttributes(long productTypeId) {
        return memo.remember(List.of("requiredAttributes", productTypeId), () -> Set.copyOf(jdbcClient.sql("""
                        SELECT definition.code
                        FROM app.product_type_required_attribute AS required
                        JOIN app.product_attribute_definition AS definition
                          ON definition.id = required.attribute_definition_id
                         AND definition.active
                        WHERE required.product_type_id = :id
                        """)
                .param("id", productTypeId)
                .query(String.class)
                .list()));
    }

    @Override
    public List<VariantWord> variantsIn(Market market, String normalizedLine) {
        if (normalizedLine == null || normalizedLine.isBlank()) {
            return List.of();
        }
        Set<String> seen = new HashSet<>();
        Set<VariantWord> found = new LinkedHashSet<>();
        for (VariantWord variant : variantWords(market)) {
            if (!seen.contains(variant.attributeCode()) && WholeWords.contains(normalizedLine, variant.word())) {
                seen.add(variant.attributeCode());
                found.add(variant);
            }
        }
        return List.copyOf(found);
    }

    private List<VariantWord> variantWords(Market market) {
        return memo.remember(List.of("variantWords", market.id(), market.defaultLanguage()), () -> jdbcClient.sql("""
                        SELECT DISTINCT ON (word.normalized_word, definition.code)
                               word.normalized_word, definition.code, word.text_value,
                               word.priority, LENGTH(word.normalized_word) AS length
                        FROM app.variant_word AS word
                        JOIN app.product_attribute_definition AS definition
                          ON definition.id = word.attribute_definition_id
                         AND definition.active
                        WHERE word.active
                          AND word.language = :language
                          AND (word.market_id = :marketId OR word.market_id IS NULL)
                        ORDER BY word.normalized_word, definition.code,
                                 (word.market_id IS NULL), word.priority, word.id
                        """)
                .param("language", market.defaultLanguage())
                .param("marketId", market.id())
                .query((rs, row) -> new Ranked(
                        new VariantWord(rs.getString("normalized_word"), rs.getString("code"),
                                rs.getString("text_value")),
                        rs.getInt("priority"),
                        rs.getInt("length")))
                .list()
                .stream()
                // Priority first, then the longer phrase: "coca cola zero" before "zero".
                .sorted((a, b) -> a.priority() != b.priority()
                        ? Integer.compare(a.priority(), b.priority())
                        : Integer.compare(b.length(), a.length()))
                .map(Ranked::variant)
                .toList());
    }

    private record Ranked(VariantWord variant, int priority, int length) {
    }
}
