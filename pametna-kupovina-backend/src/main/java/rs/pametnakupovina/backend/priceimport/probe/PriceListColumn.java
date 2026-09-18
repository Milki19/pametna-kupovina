package rs.pametnakupovina.backend.priceimport.probe;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * The columns a price list has to carry, with every spelling seen on
 * data.gov.rs. Two families publish under the same rulebook: most chains use
 * the Pravilnik header, seven use their own shorter one, and the rest differ
 * only in a dash, an asterisk or a bracketed note.
 */
public enum PriceListColumn {

    PRODUCT_NAME(true, "naziv proizvoda", "naziv_proizvoda"),
    BARCODE(true, "barkod proizvoda", "barkod proizvoda ean kod", "barkod"),
    UNIT_OF_MEASURE(true, "jedinica mere", "jedinimere", "jedinica_mere"),
    REGULAR_PRICE(true, "redovna cena", "prodajna cena rsd", "prodajna_cena_rsd"),
    PRICE_DATE(false, "datum cenovnika", "datim cenovnika"),
    PRICE_LIST_NAME(false, "naziv trgovca formata", "naziv trgovca formata*"),
    UNIT_PRICE(false, "cena po jedinici mere", "jedinicna cena rsd", "jedinicna_cena_rsd"),
    DISCOUNTED_PRICE(false, "snizena cena", "akcijska cena");

    private final boolean required;
    private final List<String> spellings;

    PriceListColumn(boolean required, String... spellings) {
        this.required = required;
        this.spellings = List.of(spellings);
    }

    public boolean required() {
        return required;
    }

    /** Serbian header. Dropped diacritics, punctuation and case. */
    public static String normalize(String header) {
        return Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .strip();
    }

    boolean matches(String header) {
        String normalized = normalize(header);

        return spellings.stream().anyMatch(spelling -> normalize(spelling).equals(normalized));
    }

    public String heading() {
        return spellings.get(0);
    }
}
