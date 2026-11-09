package rs.pametnakupovina.backend.testskup;

import rs.pametnakupovina.backend.matching.ProductNameNormalizer;
import rs.pametnakupovina.backend.shoppinglist.ParsedShoppingListLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lines a shopper actually writes, each with the products a person would
 * accept for it. One file per market and language
 * ({@code test-skup/RS-sr.tsv}), so another market brings its own words
 * instead of reusing Serbian ones.
 *
 * <p>Two layouts. The first one (7 columns) has loose lines, measured in
 * every city. The second one ({@code test-skup/RS-sr-spiskovi.tsv}, and the
 * blind part that never enters the repository) keeps real lists together:
 * every line names its list and city, and the amount the line asks for, so
 * reading the amount, step 1 and the plan are graded apart.
 */
public record TestSkup(
        String market,
        String language,
        List<Stavka> stavke,
        List<Lokacija> lokacije,
        Set<String> osnovniGradovi
) {

    private static final ProductNameNormalizer NORMALIZER = new ProductNameNormalizer();

    static final List<String> STARE_KOLONE =
            List.of("id", "tekst", "izvor", "prihvatljivo", "nije", "max_pakovanja", "opis");
    static final List<String> KOLONE_SPISKOVA =
            List.of("id", "spisak", "grad", "izvor", "tekst", "kolicina", "prihvatljivo", "nije", "max_pakovanja", "opis");

    public enum Ocena {
        /** The plan picked something a person would buy for this line. */
        ISPRAVNO,
        /** The plan picked a product, but the wrong kind or far too many packs. */
        POGRESNO,
        /** No product for this line in the plan. */
        NEMA
    }

    public record Stavka(
            String id,
            String tekst,
            String izvor,
            Pattern prihvatljivo,
            Pattern nije,
            Integer maxPakovanja,
            String opis,
            /* Which real list the line came from; null in the loose layout. */
            String spisak,
            /* The one city the line is measured in; null means every city. */
            String grad,
            /* What the line asks for; null when the set does not say. */
            Kolicina kolicina
    ) {

        public Stavka(String id, String tekst, String izvor, Pattern prihvatljivo, Pattern nije,
                Integer maxPakovanja, String opis) {
            this(id, tekst, izvor, prihvatljivo, nije, maxPakovanja, opis, null, null, null);
        }

        public Ocena oceni(String productName, BigDecimal packages) {
            if (productName == null || productName.isBlank()) {
                return Ocena.NEMA;
            }
            return razlog(productName, packages) == null ? Ocena.ISPRAVNO : Ocena.POGRESNO;
        }

        /** Why the product is wrong for this line, or null when it is fine. */
        public String razlog(String productName, BigDecimal packages) {
            String name = normalize(productName);
            if (!prihvatljivo.matcher(name).find()) {
                return "nije prihvatljiva vrsta";
            }
            if (nije != null && nije.matcher(name).find()) {
                return "pogrešna vrsta (" + nije.matcher(name).results().findFirst().orElseThrow().group() + ")";
            }
            if (maxPakovanja != null && packages != null
                    && packages.compareTo(BigDecimal.valueOf(maxPakovanja)) > 0) {
                return "previše pakovanja (" + packages.stripTrailingZeros().toPlainString() + ")";
            }
            return null;
        }

        /** Whether the line is measured in this city; loose lines only in the usual ones. */
        public boolean meriSeU(String city, Set<String> osnovniGradovi) {
            return grad == null ? osnovniGradovi.contains(city) : grad.equals(city);
        }
    }

    /**
     * The whole amount a line asks for, in the catalogue's units: "pola kile
     * sira" is 500 g, "2x pivo" is 2 kom, "voda 6x1,5l" is 9000 ml and a line
     * without any amount is 1 kom.
     */
    public record Kolicina(BigDecimal ukupno, String jedinica) {

        private static final Pattern ZAPIS = Pattern.compile(
                "(?:(?<count>\\d+)\\s*[x×]\\s*)?(?<amount>\\d+(?:[.,]\\d+)?)\\s*(?<unit>kg|g|l|ml|kom)?");

        public static Kolicina parse(String text) {
            String value = text.strip().toLowerCase(Locale.ROOT);
            if (value.equals("-")) {
                return new Kolicina(BigDecimal.ONE, "kom");
            }
            Matcher m = ZAPIS.matcher(value);
            if (!m.matches()) {
                throw new IllegalArgumentException("Količina se piše kao \"500 g\", \"1,5 kg\", \"2 l\", \"3 kom\", "
                        + "\"6 x 1,5 l\" ili \"-\", ne \"" + text + "\"");
            }
            BigDecimal amount = new BigDecimal(m.group("amount").replace(',', '.'));
            String unit = m.group("unit") == null ? "kom" : m.group("unit");
            BigDecimal count = m.group("count") == null ? BigDecimal.ONE : new BigDecimal(m.group("count"));
            return switch (unit) {
                case "kg" -> new Kolicina(count.multiply(amount).multiply(BigDecimal.valueOf(1000)), "g");
                case "l" -> new Kolicina(count.multiply(amount).multiply(BigDecimal.valueOf(1000)), "ml");
                default -> new Kolicina(count.multiply(amount), unit);
            };
        }

        /** What the parser read: packages times the amount of one, or just packages. */
        public static Kolicina of(ParsedShoppingListLine line) {
            BigDecimal count = line.quantity() == null ? BigDecimal.ONE : line.quantity();
            // "toalet papir 10 rolni": one pack of ten pieces (V157).
            if (line.piecesInPack() != null) {
                return new Kolicina(count.multiply(line.piecesInPack()), "kom");
            }
            if (line.targetQuantity() == null || line.baseUnit() == null) {
                return new Kolicina(count, "kom");
            }
            return new Kolicina(count.multiply(line.targetQuantity()), line.baseUnit());
        }

        public boolean isti(Kolicina other) {
            return other != null && jedinica.equals(other.jedinica)
                    && ukupno.setScale(1, RoundingMode.HALF_UP).compareTo(other.ukupno.setScale(1, RoundingMode.HALF_UP)) == 0;
        }

        @Override
        public String toString() {
            return ukupno.stripTrailingZeros().toPlainString() + " " + jedinica;
        }
    }

    public record Lokacija(String grad, double latitude, double longitude) {
    }

    /** The lines measured in a city. */
    public List<Stavka> u(String grad) {
        return stavke.stream().filter(s -> s.meriSeU(grad, osnovniGradovi)).toList();
    }

    public static String normalize(String productName) {
        return NORMALIZER.normalize(productName);
    }

    /** The lists in the repository: loose lines and, when there, real lists. */
    public static TestSkup load(String market, String language) {
        String base = "test-skup/" + market + "-" + language;
        List<String> files = new ArrayList<>(List.of(base + ".tsv"));
        if (TestSkup.class.getClassLoader().getResource(base + "-spiskovi.tsv") != null) {
            files.add(base + "-spiskovi.tsv");
        }
        List<Stavka> stavke = new ArrayList<>();
        for (String file : files) {
            stavke.addAll(stavke(file, resource(file)));
        }
        return of(market, language, stavke);
    }

    /** Only the given file, from the classpath or the disk: e.g. the blind part, kept outside git. */
    public static TestSkup load(String market, String language, String file) {
        Path path = Path.of(file);
        String text;
        if (Files.isRegularFile(path)) {
            try {
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            text = resource(file);
        }
        return of(market, language, stavke(path.getFileName().toString(), text));
    }

    private static TestSkup of(String market, String language, List<Stavka> stavke) {
        Set<String> ids = new LinkedHashSet<>();
        for (Stavka stavka : stavke) {
            if (!ids.add(stavka.id())) {
                throw new IllegalArgumentException("Id " + stavka.id() + " se ponavlja");
            }
        }
        List<String[]> mesta = rows("test-skup/lokacije.tsv", resource("test-skup/lokacije.tsv"), 5).stream()
                .filter(cells -> cells[0].equals(market))
                .toList();
        List<Lokacija> lokacije = mesta.stream()
                .map(cells -> new Lokacija(cells[1], Double.parseDouble(cells[2]), Double.parseDouble(cells[3])))
                .toList();
        Set<String> osnovni = new LinkedHashSet<>(mesta.stream().filter(c -> c[4].equals("da")).map(c -> c[1]).toList());
        for (Stavka stavka : stavke) {
            if (stavka.grad() != null && lokacije.stream().noneMatch(l -> l.grad().equals(stavka.grad()))) {
                throw new IllegalArgumentException(stavka.id() + ": grad " + stavka.grad()
                        + " nema polaznu tačku u test-skup/lokacije.tsv");
            }
        }
        // Loose lines go to the usual cities; any other city only for a list written there.
        boolean loose = stavke.stream().anyMatch(s -> s.grad() == null);
        List<Lokacija> used = lokacije.stream()
                .filter(l -> (loose && osnovni.contains(l.grad()))
                        || stavke.stream().anyMatch(s -> l.grad().equals(s.grad())))
                .toList();
        return new TestSkup(market, language, List.copyOf(stavke), used, Set.copyOf(osnovni));
    }

    private static List<Stavka> stavke(String name, String text) {
        List<String> header = header(text);
        List<Stavka> stavke = new ArrayList<>();
        if (header.equals(STARE_KOLONE)) {
            for (String[] c : rows(name, text, 7)) {
                stavke.add(new Stavka(c[0], c[1], c[2], regex(c[0], c[3]), optionalRegex(c[0], c[4]),
                        optionalNumber(c[5]), c[6]));
            }
        } else if (header.equals(KOLONE_SPISKOVA)) {
            for (String[] c : rows(name, text, KOLONE_SPISKOVA.size())) {
                if (c[1].isBlank() || c[2].isBlank()) {
                    throw new IllegalArgumentException(name + ": " + c[0] + " nema spisak ili grad");
                }
                stavke.add(new Stavka(c[0], c[4], c[3], regex(c[0], c[6]), optionalRegex(c[0], c[7]),
                        optionalNumber(c[8]), c[9], c[1], c[2], c[5].isBlank() ? null : Kolicina.parse(c[5])));
            }
        } else {
            throw new IllegalArgumentException(name + ": zaglavlje mora biti " + String.join(", ", KOLONE_SPISKOVA)
                    + " (ili stari oblik " + String.join(", ", STARE_KOLONE) + "), a jeste " + String.join(", ", header));
        }
        return stavke;
    }

    private static Pattern regex(String id, String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException(id + ": prazna kolona prihvatljivo");
        }
        try {
            return Pattern.compile(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(id + ": neispravan izraz " + text, e);
        }
    }

    private static Pattern optionalRegex(String id, String text) {
        return text.isEmpty() ? null : regex(id, text);
    }

    private static Integer optionalNumber(String text) {
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static String resource(String resource) {
        try (InputStream in = TestSkup.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Nema test skupa " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(String text) {
        return text.replace("\r", "").replace("﻿", "").lines()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
    }

    private static List<String> header(String text) {
        List<String> lines = lines(text);
        return lines.isEmpty() ? List.of() : List.of(lines.getFirst().split("\t", -1));
    }

    private static List<String[]> rows(String name, String text, int columns) {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = lines(text);
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
            String[] cells = line.split("\t", -1);
            if (cells.length != columns) {
                throw new IllegalArgumentException(name + ": red ima " + cells.length
                        + " kolona umesto " + columns + ": " + line);
            }
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].strip();
            }
            rows.add(cells);
        }
        return rows;
    }
}
