package rs.pametnakupovina.backend.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tables of the model contract (V155) and the code that reads them. */
@SpringBootTest
@Testcontainers
@Transactional
class ModelContractDatabaseTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    DockerImageName
                            .parse("ghcr.io/baosystems/postgis:16-3.5")
                            .asCompatibleSubstituteFor("postgres")
            )
                    .withDatabaseName("pametna_kupovina_test")
                    .withUsername("test")
                    .withPassword("test");

    @Autowired
    private JdbcClient jdbcClient;
    @Autowired
    private MarketRepository marketRepository;
    @Autowired
    private UnitVocabulary unitVocabulary;
    @Autowired
    private PieceMassCatalog pieceMassCatalog;
    @Autowired
    private KindTaxonomy kindTaxonomy;

    @Test
    void onlyFrozenFishHasABaseKind() {
        // piece_mass and sale_mode are filled by V156, unit_word by V157, variant_word and
        // product_type_required_attribute by V160. V161 gives frozen fish its base kind,
        // fresh fish; nothing else has one yet.
        assertThat(jdbcClient.sql("SELECT code FROM app.product_type WHERE base_type_id IS NOT NULL")
                .query(String.class).list()).containsExactly("FROZEN_FISH");
    }

    @Test
    void sqlAndJavaReadTheSaleModeAlike() {
        for (String override : Arrays.asList(null, "PIECE")) {
            for (String saleUnit : Arrays.asList(null, "kg", "kom")) {
                for (BigDecimal quantity : Arrays.asList(null, new BigDecimal("500"))) {
                    for (String baseUnit : Arrays.asList(null, "g", "ml", "piece")) {
                        String sql = jdbcClient.sql("SELECT app.sale_mode_of(?::varchar, ?::varchar, ?::numeric, ?::varchar)")
                                .param(override).param(saleUnit).param(quantity).param(baseUnit)
                                .query((rs, row) -> rs.getString(1)).list().get(0);
                        SaleMode java = SaleMode.of(override, saleUnit, quantity, baseUnit);
                        assertThat(sql).as("%s %s %s %s", override, saleUnit, quantity, baseUnit)
                                .isEqualTo(java == null ? null : java.name());
                    }
                }
            }
        }
    }

    @Test
    void theMarketsOwnUnitWordWinsOverTheLanguages() {
        Market market = marketRepository.defaultMarket();
        // V157 has "kom" as a COUNT for the whole language; the market's own row wins.
        jdbcClient.sql("""
                        INSERT INTO app.unit_word (market_id, language, normalized_word, role, base_unit, factor)
                        VALUES (:marketId, :language, 'kom', 'PACK', NULL, 1)
                        """)
                .param("language", market.defaultLanguage())
                .param("marketId", market.id())
                .update();
        assertThat(unitVocabulary.find(market, "kg")).hasValueSatisfying(word -> {
            assertThat(word.role()).isEqualTo(UnitWord.Role.MEASURE);
            assertThat(word.inBaseUnit(new BigDecimal("1.5"))).isEqualByComparingTo("1500");
        });
        assertThat(unitVocabulary.find(market, "kom")).hasValueSatisfying(
                word -> assertThat(word.role()).isEqualTo(UnitWord.Role.PACK));
        assertThat(unitVocabulary.words(market)).extracting(word -> word.word().length())
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(unitVocabulary.find(market, "mrkva")).isEmpty();

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO app.unit_word (language, normalized_word, role, base_unit)
                        VALUES ('sr', 'lit', 'MEASURE', NULL)
                        """).update())
                .hasMessageContaining("chk_unit_word_base_unit");
    }

    @Test
    void aPieceWeighsByTheMostPreciseKey() {
        Market market = marketRepository.defaultMarket();
        // Only this test's rows: V156 fills the table.
        jdbcClient.sql("DELETE FROM app.piece_mass").update();
        long type = newType("S0_LEMON", null);
        jdbcClient.sql("INSERT INTO app.piece_mass (product_type_id, grams, source) VALUES (:type, 110, 'test')")
                .param("type", type)
                .update();
        jdbcClient.sql("""
                        INSERT INTO app.piece_mass (language, normalized_word, grams, source)
                        VALUES (:language, 'limun', 100, 'test')
                        """)
                .param("language", market.defaultLanguage())
                .update();
        assertThat(pieceMassCatalog.gramsPerPiece(market, type, null, "limun"))
                .hasValueSatisfying(grams -> assertThat(grams).isEqualByComparingTo("110"));
        assertThat(pieceMassCatalog.gramsPerPiece(market, null, null, "limun"))
                .hasValueSatisfying(grams -> assertThat(grams).isEqualByComparingTo("100"));
        assertThat(pieceMassCatalog.gramsPerPiece(market, null, null, "avokado")).isEmpty();
        assertThat(pieceMassCatalog.gramsPerPiece(market, null, null, null)).isEmpty();
    }

    @Test
    void aBaseKindIsOneLevelDeep() {
        long fish = newType("S0_FISH", null);
        long hake = newType("S0_HAKE", fish);
        assertThat(kindTaxonomy.baseKindOf(hake)).isEqualTo(fish);
        assertThat(kindTaxonomy.baseKindOf(fish)).isEqualTo(fish);
        assertThatThrownBy(() -> newType("S0_HAKE_FILLET", hake))
                .hasMessageContaining("osnovnu vrstu");
    }

    @Test
    void aBaseKindHasNoBaseOfItsOwn() {
        long fish = newType("S0_FISH", null);
        long hake = newType("S0_HAKE", fish);
        assertThatThrownBy(() -> jdbcClient.sql("UPDATE app.product_type SET base_type_id = :hake WHERE id = :fish")
                .param("hake", hake).param("fish", fish).update())
                .hasMessageContaining("osnovn");
    }

    @Test
    void aVariantWordIsFoundOnlyWhole() {
        Market market = marketRepository.defaultMarket();
        // Only this test's rows: V160 fills the table.
        jdbcClient.sql("DELETE FROM app.variant_word").update();
        long sugar = jdbcClient.sql("""
                        INSERT INTO app.product_attribute_definition (code, name, value_kind)
                        VALUES ('S0_SUGAR', 'Šećer', 'TEXT') RETURNING id
                        """).query(Long.class).single();
        long type = newType("S0_COLA", null);
        jdbcClient.sql("""
                        INSERT INTO app.product_type_required_attribute (product_type_id, attribute_definition_id)
                        VALUES (:type, :sugar)
                        """)
                .param("type", type)
                .param("sugar", sugar)
                .update();
        jdbcClient.sql("""
                        INSERT INTO app.variant_word (language, normalized_word, attribute_definition_id, text_value)
                        VALUES (:language, 'zero', :sugar, 'ZERO'), (:language, 'light', :sugar, 'LIGHT')
                        """)
                .param("sugar", sugar)
                .param("language", market.defaultLanguage())
                .update();
        assertThat(kindTaxonomy.requiredAttributes(type)).containsExactly("S0_SUGAR");
        assertThat(kindTaxonomy.variantsIn(market, "coca cola zero 1,5l"))
                .containsExactly(new VariantWord("zero", "S0_SUGAR", "ZERO"));
        assertThat(kindTaxonomy.variantsIn(market, "coca cola zerox")).isEmpty();
        assertThat(kindTaxonomy.variantIn(market, "pepsi light", "S0_SUGAR"))
                .hasValueSatisfying(variant -> assertThat(variant.value()).isEqualTo("LIGHT"));
    }

    private long newType(String code, Long base) {
        return jdbcClient.sql("""
                        INSERT INTO app.product_type (code, name, base_type_id)
                        VALUES (:code, :code, :base) RETURNING id
                        """)
                .param("code", code)
                .param("base", base)
                .query(Long.class)
                .single();
    }
}
