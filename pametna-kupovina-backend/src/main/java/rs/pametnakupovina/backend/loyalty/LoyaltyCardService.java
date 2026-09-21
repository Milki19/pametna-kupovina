package rs.pametnakupovina.backend.loyalty;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.account.AccountRepository;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

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

    private final LoyaltyCardRepository cardRepository;
    private final AccountRepository accountRepository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;

    public LoyaltyCardService(
            LoyaltyCardRepository cardRepository,
            AccountRepository accountRepository,
            ShoppingListClientTokenPolicy clientTokenPolicy
    ) {
        this.cardRepository = cardRepository;
        this.accountRepository = accountRepository;
        this.clientTokenPolicy = clientTokenPolicy;
    }

    public List<LoyaltyCard> cards(String clientToken) {
        return cardRepository.findAll(accountFor(clientToken));
    }

    public LoyaltyCard add(
            String clientToken,
            String name,
            String cardNumber,
            String barcodeFormat
    ) {
        long accountId = accountFor(clientToken);

        if (cardRepository.findAll(accountId).size() >= MOST_CARDS) {
            throw badRequest(
                    "Više od " + MOST_CARDS + " kartica nije novčanik nego arhiva."
            );
        }

        return cardRepository.save(
                accountId,
                requiredText(name, LONGEST_NAME, "Naziv kartice"),
                validCardNumber(cardNumber),
                validFormat(barcodeFormat)
        );
    }

    public void remove(String clientToken, long cardId) {
        if (!cardRepository.delete(accountFor(clientToken), cardId)) {
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

    private long accountFor(String clientToken) {
        return accountRepository.forDevice(
                clientTokenPolicy.validateAndHash(clientToken)
        );
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
