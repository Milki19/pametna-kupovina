package rs.pametnakupovina.backend.receipt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.account.AccountRepository;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListClientTokenPolicy;

import java.util.List;

/**
 * Račun kaže šta je kupac stvarno platio — jedino po čemu aplikacija može da
 * nauči koji brend zaista kupuje i u koji lanac zaista ide. Zavodi se iz samog
 * QR koda, pa radi i kad u prodavnici nema signala za Poresku upravu.
 */
@Service
public class ReceiptService {

    private static final int MOST_RECEIPTS = 200;
    private static final int MOST_MONTHS = 24;
    private static final int MOST_SHOPS = 20;
    private static final int MOST_HABITS = 50;
    private static final String SHOP_UNKNOWN = "Nepoznata prodavnica";

    private final ReceiptRepository receiptRepository;
    private final AccountRepository accountRepository;
    private final ShoppingListClientTokenPolicy clientTokenPolicy;
    private final FiscalVerificationUrlReader verificationUrlReader;
    private final FiscalReceiptClient receiptClient;
    private final FiscalReceiptJournalParser journalParser;
    private final ReceiptItemMatcher itemMatcher;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            AccountRepository accountRepository,
            ShoppingListClientTokenPolicy clientTokenPolicy,
            FiscalVerificationUrlReader verificationUrlReader,
            FiscalReceiptClient receiptClient,
            FiscalReceiptJournalParser journalParser,
            ReceiptItemMatcher itemMatcher
    ) {
        this.receiptRepository = receiptRepository;
        this.accountRepository = accountRepository;
        this.clientTokenPolicy = clientTokenPolicy;
        this.verificationUrlReader = verificationUrlReader;
        this.receiptClient = receiptClient;
        this.journalParser = journalParser;
        this.itemMatcher = itemMatcher;
    }

    @Transactional
    public Receipt scan(String clientToken, String verificationUrl) {
        FiscalReceiptStamp stamp =
                verificationUrlReader.read(verificationUrl);
        long accountId = accountFor(clientToken);

        String shopName = stamp.shopName() == null || stamp.shopName().isBlank()
                ? SHOP_UNKNOWN
                : stamp.shopName();

        long receiptId = receiptRepository.save(
                accountId,
                stamp,
                verificationUrl.strip(),
                shopName
        );

        readItems(receiptId, verificationUrl.strip());

        return receiptRepository.findById(accountId, receiptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Račun je zaveden ali se ne može pročitati."
                ));
    }

    public List<Receipt> history(String clientToken, int limit) {
        return receiptRepository.findAll(
                accountFor(clientToken),
                Math.clamp(limit, 1, MOST_RECEIPTS)
        );
    }

    public Receipt one(String clientToken, long receiptId) {
        return receiptRepository.findById(accountFor(clientToken), receiptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Račun nije pronađen: " + receiptId
                ));
    }

    public Spending spending(String clientToken) {
        long accountId = accountFor(clientToken);

        return new Spending(
                receiptRepository.spendingByMonth(accountId, MOST_MONTHS),
                receiptRepository.spendingByShop(accountId, MOST_SHOPS)
        );
    }

    /**
     * Stavke su jedino zbog čega se Poreska uprava uopšte zove. Ako ne
     * odgovori, račun ostaje zaveden sa onim što je bilo u QR kodu — gde,
     * kada i koliko — a stavke mogu da stignu kasnije.
     */
    private void readItems(long receiptId, String verificationUrl) {
        if (receiptRepository.itemsAlreadyRead(receiptId)) {
            return;
        }

        receiptClient.fetch(verificationUrl).ifPresent(fetched -> {
            var parsed = journalParser.parse(fetched.journal());

            receiptRepository.saveItems(
                    receiptId,
                    parsed.items().stream()
                            .map(item -> item.withProductFamily(
                                    itemMatcher
                                            .productFamilyFor(item.name())
                                            .orElse(null)
                            ))
                            .toList()
            );

            // Pola računa u QR kodu uopšte ne nosi prodavnicu; Poreska uprava
            // je uvek zna, pa se ime dopunjuje čim stigne.
            String shopName = fetched.shopName() != null
                    ? fetched.shopName()
                    : parsed.shopName();

            if (shopName != null && !shopName.isBlank()) {
                receiptRepository.nameShop(
                        receiptId,
                        shopName,
                        fetched.taxIdentificationNumber() != null
                                ? fetched.taxIdentificationNumber()
                                : parsed.taxIdentificationNumber()
                );
            }
        });
    }

    /** Šta kupac obično kupuje — ono što računi znaju, a spisak ne. */
    public List<ReceiptRepository.Habit> habits(String clientToken, int limit) {
        return receiptRepository.whatTheyBuy(
                accountFor(clientToken),
                Math.clamp(limit, 1, MOST_HABITS)
        );
    }

    private long accountFor(String clientToken) {
        return accountRepository.forDevice(
                clientTokenPolicy.validateAndHash(clientToken)
        );
    }

    public record Spending(
            List<ReceiptRepository.MonthlySpending> byMonth,
            List<ReceiptRepository.ShopSpending> byShop
    ) {
    }
}
