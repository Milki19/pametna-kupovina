package rs.pametnakupovina.backend.receipt;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Poreska uprava na isti link vraća i JSON, ali stavke u njemu ne stoje kao
 * podaci — stoji otkucan račun, onakav kakav bi izašao iz kase. Zato se čita
 * taj tekst.
 *
 * <p>Artikal zauzima dva reda: naziv sa oznakom poreza u zagradi, pa red sa
 * cenom, količinom i ukupnim iznosom. Dugačak naziv se prelama, pa se redovi
 * skupljaju dok ne naiđe red sa brojevima.
 */
@Component
public class FiscalReceiptJournalParser {

    private static final Pattern AMOUNTS = Pattern.compile(
            "^\\s*(-?[\\d.]+,\\d+)\\s+(-?[\\d.]+(?:,\\d+)?)\\s+(-?[\\d.]+,\\d+)\\s*$"
    );
    private static final Pattern TAX_LABEL =
            Pattern.compile("\\s*\\([^()]{1,3}\\)\\s*$");
    private static final Pattern SEPARATOR = Pattern.compile("^[-=]{5,}$");
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{5,}$");
    private static final String ITEMS_HEADER = "Назив";
    private static final String TOTAL_LINE = "Укупан износ:";
    private static final int MOST_ITEMS = 500;

    public ParsedJournal parse(String journal) {
        if (journal == null || journal.isBlank()) {
            return new ParsedJournal(null, null, List.of());
        }

        List<String> lines = journal.lines().map(String::stripTrailing).toList();

        return new ParsedJournal(
                shopName(lines),
                taxIdentificationNumber(lines),
                items(lines)
        );
    }

    /**
     * U zaglavlju su, redom: PIB, firma, pa „broj-naziv objekta". Kupcu znači
     * objekat („PLEASURE PARK ČAIR"), ne firma, jer je to prodavnica u koju je
     * ušao.
     */
    private static String shopName(List<String> lines) {
        List<String> header = headerLines(lines);

        if (header.size() >= 3) {
            String location = header.get(2);
            int dash = location.indexOf('-');

            if (dash > 0 && DIGITS_ONLY.matcher(location.substring(0, dash)).matches()) {
                return location.substring(dash + 1).strip();
            }

            return location;
        }

        return header.size() >= 2 ? header.get(1) : null;
    }

    private static String taxIdentificationNumber(List<String> lines) {
        List<String> header = headerLines(lines);

        return header.isEmpty() || !DIGITS_ONLY.matcher(header.getFirst()).matches()
                ? null
                : header.getFirst();
    }

    /** Redovi između naslova računa i prvog reda sa dvotačkom („Касир:"). */
    private static List<String> headerLines(List<String> lines) {
        List<String> header = new ArrayList<>();
        boolean started = false;

        for (String line : lines) {
            String value = line.strip();

            if (!started) {
                started = SEPARATOR.matcher(value.replace(" ", ""))
                        .find() || value.contains("ФИСКАЛНИ РАЧУН");
                continue;
            }

            if (value.isEmpty()) {
                continue;
            }

            if (value.contains(":") || SEPARATOR.matcher(value).matches()) {
                break;
            }

            header.add(value);
        }

        return header;
    }

    private static List<Receipt.ReceiptItem> items(List<String> lines) {
        List<Receipt.ReceiptItem> items = new ArrayList<>();
        StringBuilder name = new StringBuilder();
        boolean inItems = false;

        for (String line : lines) {
            String value = line.strip();

            if (!inItems) {
                inItems = value.startsWith(ITEMS_HEADER);
                continue;
            }

            if (value.startsWith(TOTAL_LINE) || items.size() >= MOST_ITEMS) {
                break;
            }

            if (value.isEmpty() || SEPARATOR.matcher(value).matches()) {
                // Crta zatvara spisak artikala; prazan red ne znači ništa.
                if (SEPARATOR.matcher(value).matches() && !items.isEmpty()) {
                    break;
                }
                continue;
            }

            Matcher amounts = AMOUNTS.matcher(line);

            if (!amounts.matches()) {
                if (!name.isEmpty()) {
                    name.append(' ');
                }
                name.append(value);
                continue;
            }

            if (name.isEmpty()) {
                // Brojevi bez naziva iznad njih nisu artikal.
                continue;
            }

            items.add(new Receipt.ReceiptItem(
                    items.size() + 1,
                    TAX_LABEL.matcher(name.toString()).replaceAll("").strip(),
                    number(amounts.group(2)),
                    null,
                    number(amounts.group(1)),
                    number(amounts.group(3)),
                    null
            ));
            name.setLength(0);
        }

        return List.copyOf(items);
    }

    /** „1.500,00" je hiljadu petsto, ne jedan i po. */
    private static BigDecimal number(String written) {
        return new BigDecimal(written.replace(".", "").replace(',', '.'));
    }

    public record ParsedJournal(
            String shopName,
            String taxIdentificationNumber,
            List<Receipt.ReceiptItem> items
    ) {
    }
}
