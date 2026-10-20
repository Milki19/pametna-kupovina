package rs.pametnakupovina.backend.receipt.serbia;

import java.util.regex.Pattern;

/**
 * Ime prodavnice sa računa: objekat („1207756-PLEASURE PARK ČAIR") znači
 * kupcu više od firme, jer je to prodavnica u koju je ušao. Ali neki lanci
 * objekte zovu samo rednim brojem — Lidl piše „Prodavnica 137" — i onda je
 * jedino firma ta koja kaže gde je kupac bio.
 */
final class FiscalShopName {

    private static final Pattern NUMBERED_LOCATION = Pattern.compile(
            "^\\d+\\s*-\\s*"
    );
    private static final Pattern ONLY_A_NUMBER = Pattern.compile(
            "^(?:(?:mal[oa]?prodajn[ia]\\s+|prodajn[ia]\\s+)?"
                    + "(?:prodavnica|objekat|market|radnja|trgovina|poslovnica"
                    + "|poslovna\\s+jedinica|pj|maloprodaja)"
                    + "|(?:малопродајн[иа]\\s+|продајн[иа]\\s+)?"
                    + "(?:продавница|објекат|маркет|радња|трговина|пословница"
                    + "|пословна\\s+јединица|пј|малопродаја))?"
                    + "\\s*(?:br\\.?|broj|бр\\.?|број|no\\.?|#)?\\s*\\d*\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private FiscalShopName() {
    }

    /**
     * @param location objekat kako ga Poreska uprava vodi, sa brojem ispred
     * @param company  firma; koristi se kad objekat ne kaže ništa osim broja
     * @return ime za kupca, ili {@code null} kad nema ni jednog ni drugog
     */
    static String of(String location, String company) {
        String place = blankToNull(location == null
                ? null
                : NUMBERED_LOCATION.matcher(location.strip()).replaceFirst(""));
        String firm = blankToNull(company);

        if (place == null || (firm != null && ONLY_A_NUMBER.matcher(place).matches())) {
            return firm != null ? firm : place;
        }

        return place;
    }

    private static String blankToNull(String value) {
        String text = value == null ? "" : value.strip();

        return text.isEmpty() ? null : text;
    }
}
