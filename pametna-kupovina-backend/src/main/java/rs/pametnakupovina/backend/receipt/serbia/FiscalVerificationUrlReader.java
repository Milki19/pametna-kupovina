package rs.pametnakupovina.backend.receipt.serbia;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.receipt.FiscalReceiptStamp;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The QR code on a fiscal receipt is not a link to a page that happens to
 * hold the numbers — it carries them. Version, who issued it, the counters,
 * the total and the moment are all in there, signed, which is why a scanned
 * receipt can be filed with its shop, its date and its amount before the tax
 * office is asked anything at all. Only the lines have to be fetched.
 *
 * <p>This reads what a shopper's phone hands it, so it trusts nothing: the
 * address has to belong to the tax office, and every length in the code is
 * checked against what is actually there before it is used.
 */
@Component
public class FiscalVerificationUrlReader {

    private static final int VERSION = 3;
    private static final int REQUESTED_BY_AT = 1;
    private static final int SIGNED_BY_AT = 9;
    private static final int IDENTIFIER_LENGTH = 8;
    private static final int TOTAL_COUNTER_AT = 17;
    private static final int TOTAL_AMOUNT_AT = 25;
    private static final int ISSUED_AT = 33;
    // Posle vremena: vrsta računa, vrsta transakcije, pa dužina i oznaka
    // kupca (PIB ili JMBG kad kupac to traži) — ta oznaka se nikad ne čita.
    private static final int INVOICE_TYPE_AT = 41;
    private static final int TRANSACTION_TYPE_AT = 42;
    private static final int NORMAL = 0;
    private static final int ADVANCE = 4;
    private static final int SALE = 0;

    /** The amount is written in ten-thousandths of a dinar. */
    private static final BigDecimal AMOUNT_SCALE = new BigDecimal("10000");

    private static final int SHORTEST_PAYLOAD = TRANSACTION_TYPE_AT + 1;
    private static final int LONGEST_PAYLOAD = 8192;

    private final Set<String> allowedHosts;

    public FiscalVerificationUrlReader(
            @Value("${receipt.verification-hosts:suf.purs.gov.rs}")
            String allowedHosts
    ) {
        this.allowedHosts = Set.copyOf(
                List.of(allowedHosts.split(",")).stream()
                        .map(String::strip)
                        .filter(host -> !host.isEmpty())
                        .toList()
        );
    }

    /**
     * U base64 zapisu „+" je znak, a čitač adresa ga pretvara u razmak. Kase
     * štampaju QR i kodiran (%2B) i sirov (+), pa se ovde svede na kodiran —
     * i za čitanje i za poziv Poreskoj upravi.
     */
    public static String canonical(String verificationUrl) {
        return verificationUrl.strip().replace("+", "%2B");
    }

    public FiscalReceiptStamp read(String verificationUrl) {
        byte[] payload = payloadOf(canonical(verificationUrl));

        if (payload.length < SHORTEST_PAYLOAD) {
            throw badReceipt("Kod na računu je prekratak.");
        }

        if (Byte.toUnsignedInt(payload[0]) != VERSION) {
            throw badReceipt(
                    "Nepoznata verzija fiskalnog računa: "
                            + Byte.toUnsignedInt(payload[0])
            );
        }

        rejectAnythingButAPurchase(payload);

        ByteBuffer little = ByteBuffer.wrap(payload)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer big = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        String requestedBy = text(payload, REQUESTED_BY_AT, IDENTIFIER_LENGTH);
        String signedBy = text(payload, SIGNED_BY_AT, IDENTIFIER_LENGTH);
        long totalCounter =
                Integer.toUnsignedLong(little.getInt(TOTAL_COUNTER_AT));
        long amountInTenThousandths = little.getLong(TOTAL_AMOUNT_AT);

        if (amountInTenThousandths < 0) {
            throw badReceipt("Iznos na računu nije ispravan.");
        }

        // Vreme je jedino polje koje ide od najvišeg bajta.
        Instant issuedAt = Instant.ofEpochMilli(big.getLong(ISSUED_AT));

        return new FiscalReceiptStamp(
                requestedBy + "-" + signedBy + "-" + totalCounter,
                totalCounter,
                BigDecimal.valueOf(amountInTenThousandths)
                        .divide(AMOUNT_SCALE)
                        .stripTrailingZeros()
                        .setScale(2, java.math.RoundingMode.UNNECESSARY),
                issuedAt
        );
    }

    /**
     * U troškove ide samo ono što je plaćeno: račun ili avans za prodaju.
     * Povraćaj, predračun, kopija i obuka kase nisu kupovina.
     */
    private static void rejectAnythingButAPurchase(byte[] payload) {
        int invoiceType = Byte.toUnsignedInt(payload[INVOICE_TYPE_AT]);
        int transactionType = Byte.toUnsignedInt(payload[TRANSACTION_TYPE_AT]);

        if (transactionType != SALE) {
            throw badReceipt("Ovo je račun za povraćaj novca, pa se ne upisuje u troškove.");
        }
        if (invoiceType != NORMAL && invoiceType != ADVANCE) {
            throw badReceipt(switch (invoiceType) {
                case 1 -> "Ovo je predračun, a ne račun o kupovini.";
                case 2 -> "Ovo je kopija računa. Skeniraj original.";
                case 3 -> "Ovo je račun za obuku kase, ne prava kupovina.";
                default -> "Nepoznata vrsta računa: " + invoiceType;
            });
        }
    }

    private byte[] payloadOf(String verificationUrl) {
        URI uri;

        try {
            uri = URI.create(verificationUrl.strip());
        } catch (IllegalArgumentException malformed) {
            throw badReceipt("Adresa sa računa nije ispravna.");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !allowedHosts.contains(uri.getHost().toLowerCase())) {
            throw badReceipt(
                    "Ovo nije adresa Poreske uprave, pa se ne čita."
            );
        }

        String query = uri.getRawQuery();

        if (query == null) {
            throw badReceipt("U adresi nema koda računa.");
        }

        String encoded = null;

        for (String part : query.split("&")) {
            if (part.startsWith("vl=")) {
                encoded = URLDecoder.decode(
                        part.substring(3),
                        StandardCharsets.UTF_8
                );
            }
        }

        if (encoded == null || encoded.isBlank()) {
            throw badReceipt("U adresi nema koda računa.");
        }

        if (encoded.length() > LONGEST_PAYLOAD) {
            throw badReceipt("Kod na računu je predugačak.");
        }

        try {
            return Base64.getDecoder().decode(padded(encoded));
        } catch (IllegalArgumentException unreadable) {
            throw badReceipt("Kod na računu se ne može pročitati.");
        }
    }

    /** Poreska uprava ponekad izostavi dopunu na kraju base64 zapisa. */
    private static String padded(String encoded) {
        int missing = (4 - encoded.length() % 4) % 4;

        return encoded + "=".repeat(missing);
    }

    private static String text(byte[] payload, int at, int length) {
        return new String(payload, at, length, StandardCharsets.UTF_8).strip();
    }

    private static ResponseStatusException badReceipt(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
