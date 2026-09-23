package rs.pametnakupovina.backend.priceimport.probe;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a published price list without saving anything and says whether the
 * chain behind it can go live. A chain that publishes prices we cannot read,
 * or a file from last month, would quietly poison every plan, so it stops
 * here instead.
 */
@Service
public class PriceListProbeService {

    /** Enough of any published list to judge it; the largest one has ~500k rows. */
    private static final long MAX_PROBED_ROWS = 300_000L;
    private static final int MAX_LISTED_PRICE_LISTS = 20;
    private static final int MAX_FINDINGS = 12;

    private static final int MIN_ROWS = 100;
    private static final int MIN_USABLE_SHARE = 95;
    private static final int MIN_PRICE_SHARE = 98;
    private static final int MIN_BARCODE_SHARE = 80;
    private static final int UNREADABLE_SHARE = 50;
    private static final int MAX_PRICE_AGE_DAYS = 3;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("d.M.yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy")
    );

    public PriceListProbeReport probe(
            String label,
            InputStream csv,
            LocalDate today
    ) throws IOException {
        return probe(label, csv, today, null);
    }

    /**
     * @param publishedOn the day the portal last replaced the file; smaller
     *                    chains date the list "from the first of the month"
     *                    and republish it whenever a price changes.
     */
    public PriceListProbeReport probe(
            String label,
            InputStream csv,
            LocalDate today,
            LocalDate publishedOn
    ) throws IOException {
        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(csv, StandardCharsets.UTF_8)
                )
        ) {
            skipByteOrderMark(reader);
            char delimiter = detectDelimiter(reader);

            try (
                    CSVParser parser = CSVFormat.DEFAULT.builder()
                            .setDelimiter(delimiter)
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setIgnoreEmptyLines(true)
                            .setIgnoreSurroundingSpaces(true)
                            .setTrim(true)
                            .get()
                            .parse(reader)
            ) {
                return read(label, parser, today, publishedOn);
            } catch (IllegalArgumentException unreadableHeader) {
                // Npr. PWW: naslov „Ц Е Н О В Н И К" u prvom redu, pa prazne
                // kolone umesto zaglavlja. To je presuda, ne greška servera.
                return new PriceListProbeReport(
                        label, 0, 0, 0, 0, List.of(), null, null, List.of(),
                        List.of("Prvi red nije zaglavlje kolona, pa se fajl ne može pročitati: "
                                + unreadableHeader.getMessage()),
                        PriceListProbeVerdict.REJECTED
                );
            }
        }
    }

    private PriceListProbeReport read(
            String label,
            CSVParser parser,
            LocalDate today,
            LocalDate publishedOn
    ) {
        Map<PriceListColumn, String> headers = mapHeaders(parser.getHeaderNames());
        List<String> missing = new ArrayList<>();
        List<String> findings = new ArrayList<>();

        for (PriceListColumn column : PriceListColumn.values()) {
            if (!headers.containsKey(column)) {
                missing.add(column.heading());
            }
        }

        if (missing.stream().anyMatch(heading -> isRequired(heading))) {
            findings.add("Nedostaju obavezne kolone: " + String.join(", ", requiredMissing(missing)));

            return new PriceListProbeReport(
                    label, 0, 0, 0, 0, List.of(), null, null,
                    missing, findings, PriceListProbeVerdict.REJECTED
            );
        }

        long rowsRead = 0;
        long rowsUsable = 0;
        long rowsWithBarcode = 0;
        long rowsWithPrice = 0;
        LocalDate oldest = null;
        LocalDate newest = null;
        Set<String> priceLists = new LinkedHashSet<>();

        for (CSVRecord record : parser) {
            if (rowsRead >= MAX_PROBED_ROWS) {
                findings.add(
                        "Pregledano je prvih " + MAX_PROBED_ROWS + " redova; fajl je duži."
                );
                break;
            }

            rowsRead++;

            String name = value(record, headers, PriceListColumn.PRODUCT_NAME);
            BigDecimal price = price(value(record, headers, PriceListColumn.REGULAR_PRICE));
            String barcode = value(record, headers, PriceListColumn.BARCODE);
            String unit = value(record, headers, PriceListColumn.UNIT_OF_MEASURE);

            if (price != null) {
                rowsWithPrice++;
            }

            if (isBarcode(barcode)) {
                rowsWithBarcode++;
            }

            if (!name.isBlank() && price != null && !unit.isBlank()) {
                rowsUsable++;
            }

            String priceList = value(record, headers, PriceListColumn.PRICE_LIST_NAME);

            if (!priceList.isBlank() && priceLists.size() < MAX_LISTED_PRICE_LISTS) {
                priceLists.add(priceList);
            }

            LocalDate date = date(value(record, headers, PriceListColumn.PRICE_DATE));

            if (date != null) {
                oldest = oldest == null || date.isBefore(oldest) ? date : oldest;
                newest = newest == null || date.isAfter(newest) ? date : newest;
            }
        }

        PriceListProbeReport report = new PriceListProbeReport(
                label, rowsRead, rowsUsable, rowsWithBarcode, rowsWithPrice,
                List.copyOf(priceLists), oldest, newest, missing, findings, null
        );

        return new PriceListProbeReport(
                label, rowsRead, rowsUsable, rowsWithBarcode, rowsWithPrice,
                List.copyOf(priceLists), oldest, newest, missing,
                limited(judge(report, missing, today, publishedOn, findings)),
                verdict(report, missing, today, publishedOn)
        );
    }

    /** Everything worth telling the owner, in the order they would ask. */
    private List<String> judge(
            PriceListProbeReport report,
            List<String> missing,
            LocalDate today,
            LocalDate publishedOn,
            List<String> findings
    ) {
        List<String> all = new ArrayList<>(findings);

        if (report.rowsRead() < MIN_ROWS) {
            all.add("Cenovnik ima samo " + report.rowsRead() + " redova.");
        }

        if (report.priceShare() < MIN_PRICE_SHARE) {
            all.add("Cena je čitljiva u " + report.priceShare() + "% redova.");
        }

        if (report.usableShare() < MIN_USABLE_SHARE) {
            all.add(
                    "Upotrebljivo je " + report.usableShare()
                            + "% redova (naziv, cena i jedinica mere zajedno)."
            );
        }

        if (report.barcodeShare() < MIN_BARCODE_SHARE) {
            all.add(
                    "Barkod ima " + report.barcodeShare()
                            + "% redova, pa se proizvodi teže spajaju sa ostalim lancima."
            );
        }

        if (missing.contains(PriceListColumn.PRICE_DATE.heading())) {
            all.add("Nema kolonu sa datumom cenovnika; datum bi morao da se uzima iz fajla na portalu.");
        }

        if (missing.contains(PriceListColumn.PRICE_LIST_NAME.heading())) {
            all.add("Nema naziv formata, pa se cene ne mogu vezati za prodavnicu.");
        }

        if (report.newestPriceDate() == null && !missing.contains(PriceListColumn.PRICE_DATE.heading())) {
            all.add("Datum cenovnika se ne čita u ovom formatu.");
        }

        if (publishedOn != null && today.toEpochDay() - publishedOn.toEpochDay() > MAX_PRICE_AGE_DAYS) {
            all.add("Fajl na portalu nije osvežen od " + publishedOn + ".");
        }

        if (report.newestPriceDate() != null) {
            long days = today.toEpochDay() - report.newestPriceDate().toEpochDay();

            if (days > MAX_PRICE_AGE_DAYS && isFresh(publishedOn, today)) {
                // Half the smaller chains date the list "from the first of the
                // month" and republish the same file whenever a price changes.
                all.add(
                        "Cenovnik nosi datum " + report.newestPriceDate()
                                + ", a fajl je objavljen " + publishedOn
                                + " — izgleda kao mesečni cenovnik."
                );
            } else if (days > MAX_PRICE_AGE_DAYS) {
                all.add("Najnoviji datum u cenovniku je star " + days + " dana.");
            }

            // Rows carry their own "price valid from" date, so a range inside one
            // file is normal; only the newest date says whether the chain is live.
        }

        if (report.priceListNames().size() > 1) {
            all.add(
                    "Lanac objavljuje " + report.priceListNames().size()
                            + " cenovnika u istom fajlu; svaki traži svoju prodavnicu."
            );
        }

        return all;
    }

    private PriceListProbeVerdict verdict(
            PriceListProbeReport report,
            List<String> missing,
            LocalDate today,
            LocalDate publishedOn
    ) {
        if (report.rowsRead() == 0 || report.usableShare() < UNREADABLE_SHARE) {
            return PriceListProbeVerdict.REJECTED;
        }

        // Fresh means the chain is still publishing: either the list carries a
        // recent date or the portal replaced the file in the last few days.
        boolean current = isFresh(report.newestPriceDate(), today) || isFresh(publishedOn, today);

        // Several lists in one file mean the chain prices its shops differently,
        // and nothing in the file says which shop gets which list.
        boolean clean = report.rowsRead() >= MIN_ROWS
                && report.priceShare() >= MIN_PRICE_SHARE
                && report.usableShare() >= MIN_USABLE_SHARE
                && report.barcodeShare() >= MIN_BARCODE_SHARE
                && report.priceListNames().size() <= 1
                && missing.stream().noneMatch(this::isRecommended)
                && current;

        return clean ? PriceListProbeVerdict.READY : PriceListProbeVerdict.NEEDS_REVIEW;
    }

    private boolean isFresh(LocalDate date, LocalDate today) {
        return date != null && today.toEpochDay() - date.toEpochDay() <= MAX_PRICE_AGE_DAYS;
    }

    private Map<PriceListColumn, String> mapHeaders(List<String> headerNames) {
        Map<PriceListColumn, String> mapped = new EnumMap<>(PriceListColumn.class);

        for (String header : headerNames) {
            for (PriceListColumn column : PriceListColumn.values()) {
                if (!mapped.containsKey(column) && column.matches(header)) {
                    mapped.put(column, header);
                }
            }
        }

        return mapped;
    }

    private String value(
            CSVRecord record,
            Map<PriceListColumn, String> headers,
            PriceListColumn column
    ) {
        String header = headers.get(column);

        if (header == null || !record.isMapped(header) || !record.isSet(header)) {
            return "";
        }

        String value = record.get(header);

        return value == null ? "" : value.strip();
    }

    /** "1.234,56" and "1234.56" are the same price written by two chains. */
    private BigDecimal price(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String cleaned = value.replace(" ", "").replace(" ", "");

        if (cleaned.contains(",") && cleaned.contains(".")) {
            cleaned = cleaned.replace(".", "").replace(',', '.');
        } else if (cleaned.contains(",")) {
            cleaned = cleaned.replace(',', '.');
        }

        try {
            BigDecimal price = new BigDecimal(cleaned);

            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String cleaned = value.strip();

        if (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        try {
            return LocalDate.parse(cleaned);
        } catch (DateTimeParseException ignored) {
            // The other half of the country writes 17.9.2026.
        }

        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format);
            } catch (DateTimeParseException ignored) {
                // Try the next spelling.
            }
        }

        return null;
    }

    private boolean isBarcode(String value) {
        if (value == null) {
            return false;
        }

        String digits = value.strip();

        return digits.length() >= 8 && digits.length() <= 14 && digits.chars().allMatch(Character::isDigit);
    }

    private boolean isRequired(String heading) {
        return columnFor(heading).map(PriceListColumn::required).orElse(false);
    }

    private boolean isRecommended(String heading) {
        return columnFor(heading)
                .map(column -> column == PriceListColumn.PRICE_DATE
                        || column == PriceListColumn.PRICE_LIST_NAME)
                .orElse(false);
    }

    private java.util.Optional<PriceListColumn> columnFor(String heading) {
        return java.util.Arrays.stream(PriceListColumn.values())
                .filter(column -> column.heading().equals(heading))
                .findFirst();
    }

    private List<String> requiredMissing(List<String> missing) {
        return missing.stream().filter(this::isRequired).toList();
    }

    private List<String> limited(List<String> findings) {
        return findings.size() <= MAX_FINDINGS
                ? List.copyOf(findings)
                : List.copyOf(findings.subList(0, MAX_FINDINGS));
    }

    private void skipByteOrderMark(BufferedReader reader) throws IOException {
        reader.mark(1);

        if (reader.read() != 0xFEFF) {
            reader.reset();
        }
    }

    /** Most chains use a semicolon, a few a comma; nobody declares which. */
    private char detectDelimiter(BufferedReader reader) throws IOException {
        reader.mark(64 * 1024);
        String firstLine = reader.readLine();
        reader.reset();

        if (firstLine == null) {
            return ';';
        }

        long semicolons = firstLine.chars().filter(character -> character == ';').count();
        long commas = firstLine.chars().filter(character -> character == ',').count();
        long tabs = firstLine.chars().filter(character -> character == '\t').count();

        if (tabs > semicolons && tabs > commas) {
            return '\t';
        }

        return commas > semicolons ? ',' : ';';
    }
}
