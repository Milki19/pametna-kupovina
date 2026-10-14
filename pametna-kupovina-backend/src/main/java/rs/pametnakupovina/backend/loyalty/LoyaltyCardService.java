package rs.pametnakupovina.backend.loyalty;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.matching.EanValidator;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kartica lojalnosti je broj koji kasirka mora da skenira. Aplikacija je drži
 * uz nalog, pa gomila plastike ostaje kod kuće i prelazi na nov telefon sama.
 */
@Service
public class LoyaltyCardService {

    private static final int MOST_CARDS = 60;
    private static final int LONGEST_NAME = 120;
    private static final int LONGEST_NUMBER = 80;
    private static final Pattern CARD_NUMBER =
            Pattern.compile("[A-Za-z0-9 .:/_-]{4,80}");
    private static final Set<String> FORMATS = Set.of(
            "EAN_13", "EAN_8", "UPC_A", "UPC_E",
            "CODE_128", "CODE_39", "ITF", "QR_CODE", "PDF417", "CODABAR"
    );

    private static final EanValidator EAN = new EanValidator();

    private final LoyaltyCardRepository cardRepository;

    public LoyaltyCardService(
            LoyaltyCardRepository cardRepository
    ) {
        this.cardRepository = cardRepository;
    }

    public List<LoyaltyCard> cards(long accountId) {
        return cardRepository.findAll(accountId);
    }

    public LoyaltyCard add(
            long accountId,
            String name,
            String cardNumber,
            String barcodeFormat
    ) {

        if (cardRepository.findAll(accountId).size() >= MOST_CARDS) {
            throw badRequest(
                    "Više od " + MOST_CARDS + " kartica nije novčanik nego arhiva."
            );
        }

        String number = validCardNumber(cardNumber);
        String format = validFormat(barcodeFormat);

        // EAN-13 sa pogrešnom kontrolnom cifrom kasa ne čita; kao i u web
        // formi, takav broj ide kao „Crtični kod“ (Code 128).
        if ("EAN_13".equals(format)
                && (number.length() != 13 || !EAN.isValid(number))) {
            throw badRequest(number.matches("\\d{13}")
                    ? "Ovo nije ispravan EAN-13: poslednja (kontrolna) cifra se ne slaže. Proveri broj."
                    : "EAN-13 ima tačno 13 cifara. Proveri broj ili izaberi „Crtični kod“.");
        }

        return cardRepository.save(
                accountId,
                requiredText(name, LONGEST_NAME, "Naziv kartice"),
                number,
                format
        );
    }

    public void remove(long accountId, long cardId) {
        if (!cardRepository.delete(accountId, cardId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Kartica nije pronađena: " + cardId
            );
        }
    }

    private static String requiredText(String value, int longest, String what) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty()) {
            throw badRequest(what + " ne sme biti prazan.");
        }

        if (text.length() > longest) {
            throw badRequest(
                    what + " ne sme biti duži od " + longest + " znakova."
            );
        }

        return text;
    }

    /**
     * Broj se prikazuje kao crtični kod; znakovi koje nijedna kasa ne ume da
     * pročita znače da je nešto drugo završilo u tom polju.
     */
    private static String validCardNumber(String cardNumber) {
        String number = requiredText(cardNumber, LONGEST_NUMBER, "Broj kartice");

        if (!CARD_NUMBER.matcher(number).matches()) {
            throw badRequest("Broj kartice sadrži znakove koje kasa ne čita.");
        }

        return number;
    }

    private static String validFormat(String barcodeFormat) {
        String format = barcodeFormat == null || barcodeFormat.isBlank()
                ? "CODE_128"
                : barcodeFormat.strip().toUpperCase(java.util.Locale.ROOT);

        if (!FORMATS.contains(format)) {
            throw badRequest("Nepoznat oblik crtičnog koda: " + format);
        }

        return format;
    }


    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
