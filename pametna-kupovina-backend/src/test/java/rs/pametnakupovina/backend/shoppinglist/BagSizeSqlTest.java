package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.product.CatalogMemo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bag check glued "CASE WHEN" to "EXISTS": a text block drops the space
 * at its line end, and Postgres answered "syntax error at or near SELECT"
 * for every pasted list (proba V166, 11.11.2026).
 */
class BagSizeSqlTest {

    private final JdbcClient jdbcClient = mock(JdbcClient.class, RETURNS_DEEP_STUBS);

    private final ShoppingIntentResolver resolver =
            new ShoppingIntentResolver(jdbcClient, new ProductNameNormalizer(), new CatalogMemo(false));

    {
        when(jdbcClient.sql(anyString()).param(anyInt(), any()).query(Boolean.class).single()).thenReturn(true);
    }

    @Test
    void theKindCheckKeepsItsSpaces() {
        resolver.kindHoldsItsSize(7L);

        assertSpaced(sql());
    }

    @Test
    void theNameCheckKeepsItsSpaces() {
        resolver.namesHoldTheirSize("\\mkese\\M.*\\msmec[a-z]*\\M");

        assertSpaced(sql());
    }

    private String sql() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(jdbcClient, atLeastOnce()).sql(captor.capture());
        return captor.getValue();
    }

    private static void assertSpaced(String sql) {
        assertThat(sql).doesNotContainPattern("WHEN\\S|\\)THEN");
        assertThat(sql.replaceAll("\\s+", " ")).contains("CASE WHEN EXISTS ( SELECT 1 FROM app.capacity_rule");
        assertThat(sql.replaceAll("\\s+", " ")).contains(") THEN 1.0 ELSE 0.0 END");
    }
}
