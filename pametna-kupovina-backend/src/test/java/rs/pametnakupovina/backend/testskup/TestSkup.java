package rs.pametnakupovina.backend.testskup;

import rs.pametnakupovina.backend.matching.ProductNameNormalizer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lines a shopper actually writes, each with the products a person would
 * accept for it. One file per market and language
 * ({@code test-skup/RS-sr.tsv}), so another market brings its own words
 * instead of reusing Serbian ones.
 */
public record TestSkup(String market, String language, List<Stavka> stavke, List<Lokacija> lokacije) {

    private static final ProductNameNormalizer NORMALIZER = new ProductNameNormalizer();

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
            String opis
    ) {

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
    }

    public record Lokacija(String grad, double latitude, double longitude) {
    }

    public static String normalize(String productName) {
        return NORMALIZER.normalize(productName);
    }

    public static TestSkup load(String market, String language) {
        List<Stavka> stavke = new ArrayList<>();
        for (String[] cells : rows("test-skup/" + market + "-" + language + ".tsv", 7)) {
            stavke.add(new Stavka(
                    cells[0],
                    cells[1],
                    cells[2],
                    Pattern.compile(cells[3]),
                    cells[4].isEmpty() ? null : Pattern.compile(cells[4]),
                    cells[5].isEmpty() ? null : Integer.valueOf(cells[5]),
                    cells[6]
            ));
        }
        List<Lokacija> lokacije = rows("test-skup/lokacije.tsv", 4).stream()
                .filter(cells -> cells[0].equals(market))
                .map(cells -> new Lokacija(cells[1], Double.parseDouble(cells[2]), Double.parseDouble(cells[3])))
                .toList();
        return new TestSkup(market, language, List.copyOf(stavke), lokacije);
    }

    private static List<String[]> rows(String resource, int columns) {
        try (InputStream in = TestSkup.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Nema test skupa " + resource);
            }
            List<String[]> rows = new ArrayList<>();
            boolean header = true;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] cells = line.split("\t", -1);
                if (cells.length != columns) {
                    throw new IllegalArgumentException(resource + ": red ima " + cells.length
                            + " kolona umesto " + columns + ": " + line);
                }
                rows.add(cells);
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
