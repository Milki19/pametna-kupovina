package rs.pametnakupovina.backend.receipt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
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
    private static final ZoneId BELGRADE = ZoneId.of("Europe/Belgrade");

    private final ReceiptRepository receiptRepository;
    private final FiscalVerificationUrlReader verificationUrlReader;
    private final FiscalReceiptClient receiptClient;
    private final FiscalReceiptJournalParser journalParser;
    private final ReceiptItemMatcher itemMatcher;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            FiscalVerificationUrlReader verificationUrlReader,
            FiscalReceiptClient receiptClient,
            FiscalReceiptJournalParser journalParser,
            ReceiptItemMatcher itemMatcher
    ) {
        this.receiptRepository = receiptRepository;
        this.verificationUrlReader = verificationUrlReader;
        this.receiptClient = receiptClient;
        this.journalParser = journalParser;
        this.itemMatcher = itemMatcher;
    }

    @Transactional
    public Receipt scan(long accountId, String scannedUrl) {
        String verificationUrl = FiscalVerificationUrlReader.canonical(scannedUrl);
        FiscalReceiptStamp stamp = verificationUrlReader.read(verificationUrl);

        // Ime prodavnice daje tek stranica Poreske uprave (readItems).
        long receiptId = receiptRepository.save(
                accountId,
                stamp,
                verificationUrl,
                SHOP_UNKNOWN
        );

        readItems(receiptId, verificationUrl);

        return receiptRepository.findById(accountId, receiptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Račun je zaveden ali se ne može pročitati."
                ));
    }

    public List<Receipt> history(long accountId, int limit) {
        return receiptRepository.findAll(
                accountId,
                Math.clamp(limit, 1, MOST_RECEIPTS)
        );
    }

    public Receipt one(long accountId, long receiptId) {
        return receiptRepository.findById(accountId, receiptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Račun nije pronađen: " + receiptId
                ));
    }

    public Spending spending(long accountId, LocalDate month) {
        LocalDate weekMonth = (month != null ? month : LocalDate.now(BELGRADE))
                .withDayOfMonth(1);

        return new Spending(
                receiptRepository.spendingByMonth(accountId, MOST_MONTHS),
                receiptRepository.spendingByShop(accountId, MOST_SHOPS),
                receiptRepository.spendingByWeek(accountId, weekMonth),
                receiptRepository.spendingByCategory(accountId, weekMonth)
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
    public List<ReceiptRepository.Habit> habits(long accountId, int limit) {
        return receiptRepository.whatTheyBuy(
                accountId,
                Math.clamp(limit, 1, MOST_HABITS)
        );
    }


    public record Spending(
            List<ReceiptRepository.MonthlySpending> byMonth,
            List<ReceiptRepository.ShopSpending> byShop,
            List<ReceiptRepository.WeeklySpending> byWeek,
            List<ReceiptRepository.CategorySpending> byCategory
    ) {
    }
}
