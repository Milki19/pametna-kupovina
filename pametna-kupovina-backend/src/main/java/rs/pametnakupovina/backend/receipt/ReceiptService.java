package rs.pametnakupovina.backend.receipt;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import rs.pametnakupovina.backend.market.Market;
import rs.pametnakupovina.backend.market.MarketRepository;

import java.time.LocalDate;
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
    private final MarketRepository marketRepository;
    private final ReceiptReaders receiptReaders;
    private final ReceiptItemMatcher itemMatcher;

    public ReceiptService(
            ReceiptRepository receiptRepository,
            MarketRepository marketRepository,
            ReceiptReaders receiptReaders,
            ReceiptItemMatcher itemMatcher
    ) {
        this.receiptRepository = receiptRepository;
        this.marketRepository = marketRepository;
        this.receiptReaders = receiptReaders;
        this.itemMatcher = itemMatcher;
    }

    @Transactional
    public Receipt scan(long accountId, String scannedUrl) {
        // A receipt is read the way the shopper's market prints them.
        ReceiptReader reader = receiptReaders.forMarket(
                marketRepository.forAccount(accountId)
        );
        ReceiptReader.ScannedReceipt scanned = reader.read(scannedUrl);

        // Ime prodavnice daje tek stranica Poreske uprave (readItems).
        long receiptId = receiptRepository.save(
                accountId,
                scanned.stamp(),
                scanned.address(),
                SHOP_UNKNOWN
        );

        readItems(reader, receiptId, scanned.address());

        return receiptRepository.findById(accountId, receiptId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Račun je zaveden ali se ne može pročitati."
                ));
    }

    /**
     * Digitalni račun: kodovi sa screenshot-a ili PDF-a. Na fajlu može biti više QR kodova
     * (kupon, link trgovine); zavodi se prvi koji čitač tržišta prihvati kao
     * račun, a ako nijedan, kupac dobija razlog za prvi koji je probao.
     */
    @Transactional
    public Receipt scanCodes(long accountId, List<String> codes) {
        if (codes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Na slici nema QR koda. Pošalji screenshot na kom se ceo QR kod računa vidi."
            );
        }

        ResponseStatusException firstRefusal = null;

        for (String code : codes) {
            try {
                return scan(accountId, code);
            } catch (ResponseStatusException refused) {
                if (!refused.getStatusCode().is4xxClientError()) {
                    throw refused;
                }
                if (firstRefusal == null) {
                    firstRefusal = refused;
                }
            }
        }
        throw firstRefusal;
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
        // A month is the market's month: a receipt from 23:30 on the last day
        // counts where the shopper paid it, not where the server is.
        Market market = marketRepository.forAccount(accountId);
        LocalDate weekMonth = (month != null ? month : market.today())
                .withDayOfMonth(1);

        return new Spending(
                receiptRepository.spendingByMonth(accountId, MOST_MONTHS, market.timeZone()),
                receiptRepository.spendingByShop(accountId, MOST_SHOPS),
                receiptRepository.spendingByWeek(accountId, weekMonth, market.timeZone()),
                receiptRepository.spendingByCategory(accountId, weekMonth, market.timeZone())
        );
    }

    /**
     * Stavke su jedino zbog čega se Poreska uprava uopšte zove. Ako ne
     * odgovori, račun ostaje zaveden sa onim što je bilo u QR kodu — gde,
     * kada i koliko — a stavke mogu da stignu kasnije.
     */
    private void readItems(ReceiptReader reader, long receiptId, String address) {
        if (receiptRepository.itemsAlreadyRead(receiptId)) {
            return;
        }

        reader.lines(address).ifPresent(lines -> {
            receiptRepository.saveItems(
                    receiptId,
                    lines.items().stream()
                            .map(item -> item.withProductFamily(
                                    itemMatcher
                                            .productFamilyFor(item.name())
                                            .orElse(null)
                            ))
                            .toList()
            );

            if (lines.shopName() != null && !lines.shopName().isBlank()) {
                receiptRepository.nameShop(
                        receiptId,
                        lines.shopName(),
                        lines.taxIdentificationNumber()
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
