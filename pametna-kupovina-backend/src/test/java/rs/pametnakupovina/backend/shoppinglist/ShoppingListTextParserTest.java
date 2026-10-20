package rs.pametnakupovina.backend.shoppinglist;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A grill list says how much meat is needed in total ("ćevapi 3kg"), never how
 * many packs, because the pack size differs per shop. Counting words keep
 * meaning packs.
 */
class ShoppingListTextParserTest {

    private final ShoppingListTextParser parser = new ShoppingListTextParser();

    private ParsedShoppingListLine parseOne(String line) {
        List<ParsedShoppingListLine> items = parser.parse(line).items();
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    @Test
    void emojiCopiedFromNotesIsNotPartOfTheName() {
        assertThat(parseOne("🍅 paradajz").name()).isEqualTo("paradajz");
        assertThat(parseOne("✔️ hleb").name()).isEqualTo("hleb");
        assertThat(parseOne("👨‍👩‍👧 mleko 🥛 1l").name()).isEqualTo("mleko");
        assertThat(parseOne("2x 🍌 banane").quantity()).isEqualByComparingTo("2");
        assertThat(parseOne("2x 🍌 banane").name()).isEqualTo("banane");
        // Without words there is nothing left to buy, and the refusal says so.
        assertThat(parseOne("🍅").name()).isEqualTo("🍅");
        assertThat(parseOne("Mleko 2,8% Imlek®").name()).isEqualTo("Mleko 2,8% Imlek®");
    }

    @Test
    void weightBecomesATotalAmountAndLeavesTheNameSearchable() {
        ParsedShoppingListLine cevapi = parseOne("Ćevapi 3kg");
        assertThat(cevapi.name()).isEqualTo("Ćevapi");
        assertThat(cevapi.targetQuantity()).isEqualByComparingTo("3000");
        assertThat(cevapi.baseUnit()).isEqualTo("g");
        assertThat(cevapi.quantity()).isEqualByComparingTo("1");
    }

    @Test
    void volumeConvertsToMillilitresAndSpelledOutUnitsAreUnderstood() {
        assertThat(parseOne("mleko 1l").targetQuantity())
                .isEqualByComparingTo("1000");
        assertThat(parseOne("mleko 1l").baseUnit()).isEqualTo("ml");
        assertThat(parseOne("sok 500ml").targetQuantity())
                .isEqualByComparingTo("500");

        ParsedShoppingListLine sir = parseOne("Sitan sir 400grama");
        assertThat(sir.name()).isEqualTo("Sitan sir");
        assertThat(sir.targetQuantity()).isEqualByComparingTo("400");
        assertThat(sir.baseUnit()).isEqualTo("g");
    }

    @Test
    void decimalsAndLeadingAmountsWork() {
        assertThat(parseOne("Vrat 1,5 kg").targetQuantity())
                .isEqualByComparingTo("1500");
        ParsedShoppingListLine leading = parseOne("2kg krilca");
        assertThat(leading.name()).isEqualTo("krilca");
        assertThat(leading.targetQuantity()).isEqualByComparingTo("2000");
    }

    @Test
    void countingWordsStillMeanPackagesNotWeight() {
        ParsedShoppingListLine pieces = parseOne("3x mleko");
        assertThat(pieces.quantity()).isEqualByComparingTo("3");
        assertThat(pieces.targetQuantity()).isNull();

        ParsedShoppingListLine suffix = parseOne("jaja 2 kom");
        assertThat(suffix.quantity()).isEqualByComparingTo("2");
        assertThat(suffix.targetQuantity()).isNull();
    }

    @Test
    void aPlainNameCarriesNoAmountAtAll() {
        ParsedShoppingListLine plain = parseOne("kiselo mleko");
        assertThat(plain.name()).isEqualTo("kiselo mleko");
        assertThat(plain.targetQuantity()).isNull();
        assertThat(plain.quantity()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void aCountAndAnAmountShareOneLine() {
        ParsedShoppingListLine line = parseOne("2x ćevapi 3kg");
        assertThat(line.quantity()).isEqualByComparingTo("2");
        assertThat(line.name()).isEqualTo("ćevapi");
        assertThat(line.targetQuantity()).isEqualByComparingTo("3000");
    }

    @Test
    void listMarksFromNotesAreNotPartOfTheName() {
        assertThat(parseOne("• mleko").name()).isEqualTo("mleko");
        assertThat(parseOne("- hleb").name()).isEqualTo("hleb");
        ParsedShoppingListLine numbered = parseOne("1. jaja 10 kom");
        assertThat(numbered.name()).isEqualTo("jaja");
        assertThat(numbered.quantity()).isEqualByComparingTo("10");
    }

    @Test
    void aNumberWithoutAUnitWaitsForTheKindOfProductToExplainIt() {
        ParsedShoppingListLine water = parseOne("Kisela voda 1.75");
        assertThat(water.name()).isEqualTo("Kisela voda");
        assertThat(water.bareNumber()).isEqualByComparingTo("1.75");
        assertThat(water.targetQuantity()).isNull();
        assertThat(water.nameWithAmount()).isEqualTo("Kisela voda 1.75");

        assertThat(parseOne("jaja 10").bareNumber()).isEqualByComparingTo("10");
        assertThat(parseOne("jaja 10").name()).isEqualTo("jaja");
    }

    @Test
    void aCountWrittenFirstWaitsForTheKindOfProductToo() {
        ParsedShoppingListLine eggs = parseOne("10 jaja");
        assertThat(eggs.name()).isEqualTo("jaja");
        assertThat(eggs.bareNumber()).isEqualByComparingTo("10");
        assertThat(eggs.nameWithAmount()).isEqualTo("10 jaja");
        assertThat(eggs.quantity()).isEqualByComparingTo("1");

        // One letter after the number is part of a name: "3 u 1" coffee.
        assertThat(parseOne("3 u 1").bareNumber()).isEqualByComparingTo("1");
        assertThat(parseOne("3 u 1").nameWithAmount()).isEqualTo("3 u 1");
    }

    @Test
    void cyrillicUnitsReadLikeLatinOnes() {
        ParsedShoppingListLine milk = parseOne("млеко 1л");
        assertThat(milk.name()).isEqualTo("млеко");
        assertThat(milk.targetQuantity()).isEqualByComparingTo("1000");
        assertThat(milk.baseUnit()).isEqualTo("ml");

        assertThat(parseOne("кромпир 2 кг").targetQuantity()).isEqualByComparingTo("2000");
        assertThat(parseOne("јаја 10 ком").quantity()).isEqualByComparingTo("10");
    }

    @Test
    void severalItemsOnOneLineOfAMessageBecomeSeparateItems() {
        List<ParsedShoppingListLine> items = parser.parse("Mleko, hleb, jaja 10 kom; 2x pivo").items();
        assertThat(items).extracting(ParsedShoppingListLine::name)
                .containsExactly("Mleko", "hleb", "jaja", "pivo");
        assertThat(items.get(1).rawInput()).isEqualTo("hleb");

        assertThat(parseOne("Mleko 2,8% 1l").name()).isEqualTo("Mleko 2,8%");
        assertThat(parseOne("mleko, 2l").name()).isEqualTo("mleko,");
    }

    @Test
    void theWrittenSizeStaysAvailableForALineThatNamesOneProduct() {
        ParsedShoppingListLine wine = parseOne("Rubin roze 1l");
        assertThat(wine.name()).isEqualTo("Rubin roze");
        assertThat(wine.nameWithAmount()).isEqualTo("Rubin roze 1l");
    }

    @Test
    void anAmountWithNoProductNameIsNotAnItem() {
        assertThat(parseOne("3kg").name()).isEqualTo("3kg");
        assertThat(parseOne("3kg").targetQuantity()).isNull();
    }

    @Test
    void amountsSaidInWordsAreAmounts() {
        ParsedShoppingListLine half = parseOne("pola kile sira");
        assertThat(half.name()).isEqualTo("sira");
        assertThat(half.targetQuantity()).isEqualByComparingTo("500");
        assertThat(half.baseUnit()).isEqualTo("g");
        assertThat(half.rawInput()).isEqualTo("pola kile sira");

        assertThat(parseOne("kila jabuka").targetQuantity()).isEqualByComparingTo("1000");
        assertThat(parseOne("sir kilo").targetQuantity()).isEqualByComparingTo("1000");
        assertThat(parseOne("2 kile krompira").targetQuantity()).isEqualByComparingTo("2000");
        assertThat(parseOne("vrat kilo i po").targetQuantity()).isEqualByComparingTo("1500");
        assertThat(parseOne("pola litre pavlake").targetQuantity()).isEqualByComparingTo("500");
        assertThat(parseOne("pola litre pavlake").baseUnit()).isEqualTo("ml");
        assertThat(parseOne("litar mleka").targetQuantity()).isEqualByComparingTo("1000");
        assertThat(parseOne("пола кила сира").targetQuantity()).isEqualByComparingTo("500");
        // "pola" without a kilogram or a litre stays part of the name.
        assertThat(parseOne("pola hleba").name()).isEqualTo("pola hleba");
        assertThat(parseOne("pola hleba").targetQuantity()).isNull();
    }

    @Test
    void aSizeAtTheEndOfAProductNameIsItsAmount() {
        ParsedShoppingListLine cola = parseOne("coca cola 2l");
        assertThat(cola.name()).isEqualTo("coca cola");
        assertThat(cola.targetQuantity()).isEqualByComparingTo("2000");
        assertThat(cola.baseUnit()).isEqualTo("ml");

        ParsedShoppingListLine flour = parseOne("BRAŠNO T-400 1 kg");
        assertThat(flour.name()).isEqualTo("BRAŠNO T-400");
        assertThat(flour.targetQuantity()).isEqualByComparingTo("1000");
        assertThat(flour.quantity()).isEqualByComparingTo("1");
    }

    /**
     * The colleague's test of 18.10.: a message as a wife sends it, pasted
     * whole. Every line is something to buy, read without a click.
     */
    @Test
    void aRealMessageReadsLineByLine() {
        ParsedShoppingListText message = parser.parse("""
                Kupi:
                2 mleka
                hleb
                jogurte (3)
                piletina oko 1kg
                krompir 2kg
                luk
                banane
                jaja 10
                pivo za utakmicu 6
                sapun
                kecap
                cokolada za malog
                🍅 paradajz
                prasak za ves
                sir (500g)
                """);

        assertThat(message.items()).extracting(ParsedShoppingListLine::name).containsExactly(
                "mleka", "hleb", "jogurte", "piletina", "krompir", "luk", "banane",
                "jaja", "pivo", "sapun", "kecap", "cokolada", "paradajz", "prasak za ves", "sir");
        // The heading is line 1, so "2 mleka" still points at line 2.
        assertThat(message.lineNumber(0)).isEqualTo(2);
    }

    @Test
    void aHeadingIsNotAnItem() {
        assertThat(parser.parse("Kupi:").items()).isEmpty();
        assertThat(parser.parse("🛒 Spisak za subotu:").items()).isEmpty();
        assertThat(ShoppingListTextParser.isHeading("- Kupi:")).isTrue();
        // A colon with a number is something to buy, not a heading.
        assertThat(ShoppingListTextParser.isHeading("mleko: 2")).isFalse();

        assertThat(parser.parse("Kupi: mleko, hleb").items())
                .extracting(ParsedShoppingListLine::name)
                .containsExactly("mleko", "hleb");
    }

    @Test
    void theCountInBracketsIsTheCount() {
        ParsedShoppingListLine yoghurt = parseOne("jogurte (3)");
        assertThat(yoghurt.name()).isEqualTo("jogurte");
        assertThat(yoghurt.quantity()).isEqualByComparingTo("3");
        assertThat(yoghurt.rawInput()).isEqualTo("jogurte (3)");

        assertThat(parseOne("jaja (10 kom)").quantity()).isEqualByComparingTo("10");

        ParsedShoppingListLine cheese = parseOne("sir (500g)");
        assertThat(cheese.name()).isEqualTo("sir");
        assertThat(cheese.targetQuantity()).isEqualByComparingTo("500");
        assertThat(cheese.baseUnit()).isEqualTo("g");
    }

    @Test
    void anApproximateAmountIsTheAmount() {
        ParsedShoppingListLine chicken = parseOne("piletina oko 1kg");
        assertThat(chicken.name()).isEqualTo("piletina");
        assertThat(chicken.targetQuantity()).isEqualByComparingTo("1000");
        assertThat(chicken.baseUnit()).isEqualTo("g");

        assertThat(parseOne("cca 2 kg krompira").targetQuantity()).isEqualByComparingTo("2000");
        assertThat(parseOne("mleko ~2l").targetQuantity()).isEqualByComparingTo("2000");
        // "oko" without a number stays a word.
        assertThat(parseOne("oko mleka").name()).isEqualTo("oko mleka");
    }

    @Test
    void whomItIsForIsANoteAndTheCountAfterItStaysTheCount() {
        ParsedShoppingListLine beer = parseOne("pivo za utakmicu 6");
        assertThat(beer.name()).isEqualTo("pivo");
        assertThat(beer.bareNumber()).isEqualByComparingTo("6");
        assertThat(ShoppingListTextParser.noteOf(beer.rawInput())).isEqualTo("za utakmicu");

        ParsedShoppingListLine chocolate = parseOne("cokolada za malog");
        assertThat(chocolate.name()).isEqualTo("cokolada");
        assertThat(chocolate.rawInput()).isEqualTo("cokolada za malog");
        assertThat(ShoppingListTextParser.noteOf("cokolada za malog")).isEqualTo("za malog");

        assertThat(parseOne("keks (za malu)").name()).isEqualTo("keks");
        assertThat(ShoppingListTextParser.noteOf("keks (za malu)")).isEqualTo("za malu");
        assertThat(parseOne("Čokolada za Malog").name()).isEqualTo("Čokolada");

        // These name what is bought, so they stay whole and carry no note.
        for (String product : List.of("prasak za ves", "hrana za pse", "kore za pitu",
                "rezanci za supu", "deterdžent za sudove")) {
            assertThat(parseOne(product).name()).as(product).isEqualTo(product);
            assertThat(ShoppingListTextParser.noteOf(product)).as(product).isNull();
        }
    }

    @Test
    void aPackOfBottlesIsHowManyOfWhichSize() {
        ParsedShoppingListLine water = parseOne("negazirana voda 6x1,5l");
        assertThat(water.name()).isEqualTo("negazirana voda");
        assertThat(water.quantity()).isEqualByComparingTo("6");
        assertThat(water.targetQuantity()).isEqualByComparingTo("1500");
        assertThat(water.baseUnit()).isEqualTo("ml");

        ParsedShoppingListLine beer = parseOne("pivo 6x0,5");
        assertThat(beer.name()).isEqualTo("pivo");
        assertThat(beer.quantity()).isEqualByComparingTo("6");
        assertThat(beer.bareNumber()).isEqualByComparingTo("0.5");

        ParsedShoppingListLine first = parseOne("6x1,5l kisele vode");
        assertThat(first.name()).isEqualTo("kisele vode");
        assertThat(first.quantity()).isEqualByComparingTo("6");
        assertThat(first.targetQuantity()).isEqualByComparingTo("1500");
    }

    @Test
    void aMessageSaysWhatToDoBeforeTheItems() {
        ParsedShoppingListText text = parser.parse("uzmi 2 hleba i pola kile sira\n- kupi mleko\nkupus");
        assertThat(text.items()).extracting(ParsedShoppingListLine::name)
                .containsExactly("hleba", "sira", "mleko", "kupus");
        assertThat(text.items().get(0).bareNumber()).isEqualByComparingTo("2");
        assertThat(text.items().get(1).targetQuantity()).isEqualByComparingTo("500");
    }
}
